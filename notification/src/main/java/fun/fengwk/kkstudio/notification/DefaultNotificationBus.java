package fun.fengwk.kkstudio.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.share.notification.Notification;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationPacket;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import javax.sql.DataSource;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** One bus recognizes only the actual Spring physical transaction for its own DataSource. */
@Slf4j
public final class DefaultNotificationBus implements NotificationBus {
  private record Publication(Notification<?> notification, NotificationPacket wire) {}

  private record Hint(String topic, UUID target, byte[] bytes) {
    static Hint of(NotificationPacket wire) {
      return new Hint(wire.topic(), wire.target(), wire.bytes());
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Hint hint
          && topic.equals(hint.topic)
          && Objects.equals(target, hint.target)
          && Arrays.equals(bytes, hint.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(topic, target) + Arrays.hashCode(bytes);
    }
  }

  private final DataSource dataSource;
  private final UUID nodeId;
  private final NotificationLimits limits;
  private final Map<String, NotificationTopic<?>> topics = new HashMap<>();
  private final LocalInbox inbox;
  private final CrossNodeTransport transport;
  private final NotificationRouter router;
  private volatile boolean closed;

  public DefaultNotificationBus(
      DataSource dataSource,
      UUID nodeId,
      Collection<NotificationTopic<?>> topics,
      NotificationLimits limits,
      Duration pollInterval,
      Duration reconnectBackoff) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.nodeId = Objects.requireNonNull(nodeId);
    this.limits = Objects.requireNonNull(limits);
    for (NotificationTopic<?> topic : topics) {
      if (this.topics.putIfAbsent(topic.name(), topic) != null) {
        throw new IllegalArgumentException("duplicate notification topic: " + topic.name());
      }
    }
    if (this.topics.isEmpty()) {
      throw new IllegalArgumentException("topics required");
    }
    this.inbox = new LocalInbox(limits);
    this.transport =
        new PgTransport(
            dataSource,
            nodeId,
            limits,
            pollInterval,
            reconnectBackoff,
            this::receive,
            inbox::resync,
            inbox::resyncAll);
    this.router = new NotificationRouter(nodeId, inbox, transport);
  }

  public void start() {
    ensureOpen();
    transport.start();
  }

  public boolean healthy() {
    return !closed && transport.healthy() && inbox.healthy();
  }

  @Override
  public UUID nodeId() {
    return nodeId;
  }

  @Override
  public <T> void publish(NotificationTopic<T> topic, NotificationAddress address, T payload) {
    publishBatch(topic, address, List.of(payload));
  }

  @Override
  public <T> void publishBatch(
      NotificationTopic<T> topic, NotificationAddress address, List<T> payloads) {
    ensureBound(topic);
    Objects.requireNonNull(address);
    List<Publication> publications = new ArrayList<>();
    long bytes = 0;
    if (payloads.size() > limits.queueCapacity()) {
      throw new IllegalArgumentException("notification batch too large");
    }
    // Validate the complete batch before producing any side effect.
    for (T payload : payloads) {
      byte[] encoded = topic.codec().encode(Objects.requireNonNull(payload));
      if (encoded.length > limits.maxMessageBytes()
          || (bytes += encoded.length) > limits.pendingBytes()) {
        throw new IllegalArgumentException("notification batch exceeds byte budget");
      }
      UUID id = UUID.randomUUID();
      Notification<T> notification = new Notification<>(id, nodeId, address, topic, payload);
      NotificationPacket wire =
          new NotificationPacket(nodeId, address.nodeId(), topic.name(), id, encoded);
      publications.add(new Publication(notification, wire));
    }
    ConnectionHolder holder = physicalTransaction();
    if (holder == null) {
      for (Publication publication : publications) {
        router.remote(publication.wire(), false);
        router.local(publication.notification(), publication.wire().byteLength());
      }
    } else {
      CommitBatch batch = commitBatch();
      try {
        for (Publication publication : publications) {
          if (batch.add(publication)) {
            router.remote(publication.wire(), true);
          }
        }
      } catch (RuntimeException error) {
        holder.setRollbackOnly();
        throw error;
      }
    }
  }

  @Override
  public <T> NotificationSubscription subscribe(
      NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync) {
    ensureBound(topic);
    return inbox.subscribe(topic, Objects.requireNonNull(consumer), Objects.requireNonNull(resync));
  }

  private void receive(NotificationPacket wire) {
    NotificationTopic<?> topic = topics.get(wire.topic());
    if (topic != null) {
      try {
        decodeAndAccept(topic, wire);
      } catch (RuntimeException error) {
        log.warn(
            "Notification decode failed topic={} errorType={}",
            topic.name(),
            error.getClass().getSimpleName());
        inbox.resync(topic.name());
      }
    }
  }

  private <T> void decodeAndAccept(NotificationTopic<T> topic, NotificationPacket wire) {
    T payload = topic.codec().decode(wire.bytes());
    Notification<T> notification =
        new Notification<>(
            wire.messageId(),
            wire.publisher(),
            new NotificationAddress(wire.target()),
            topic,
            payload);
    inbox.accept(notification, wire.byteLength());
  }

  private ConnectionHolder physicalTransaction() {
    if (NotificationTransactionManager.isPostCommit()) {
      throw new IllegalStateException("publish cannot run on an afterCommit thread");
    }
    Object resource = TransactionSynchronizationManager.getResource(dataSource);
    if (!(resource instanceof ConnectionHolder holder)) {
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new IllegalStateException(
            "notification DataSource is not bound to the active transaction");
      }
      return null;
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || !holder.isSynchronizedWithTransaction()) {
      throw new IllegalStateException(
          "DataSource connection is not in a physical Spring transaction");
    }
    try {
      if (holder.getConnection().getAutoCommit()) {
        throw new IllegalStateException("transaction connection is auto-commit");
      }
    } catch (SQLException error) {
      holder.setRollbackOnly();
      throw new IllegalStateException("cannot inspect notification transaction", error);
    }
    return holder;
  }

  private CommitBatch commitBatch() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof DefaultNotificationBus.CommitBatch batch
          && batch.owner() == this) {
        if (batch.committed) {
          throw new IllegalStateException("publish cannot run on an afterCommit thread");
        }
        return batch;
      }
    }
    if (NotificationTransactionManager.isPostCommit()) {
      throw new IllegalStateException("publish cannot run on an afterCommit thread");
    }
    CommitBatch batch = new CommitBatch();
    TransactionSynchronizationManager.registerSynchronization(batch);
    return batch;
  }

  private void ensureBound(NotificationTopic<?> topic) {
    ensureOpen();
    if (topics.get(topic.name()) != topic) {
      throw new IllegalArgumentException("topic is not statically bound");
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("notification bus closed");
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      try {
        inbox.close();
      } finally {
        transport.close();
      }
    }
  }

  private final class CommitBatch implements TransactionSynchronization {
    final List<Publication> publications = new ArrayList<>();
    final Map<Hint, Publication> hints = new HashMap<>();
    long bytes;
    boolean committed;

    DefaultNotificationBus owner() {
      return DefaultNotificationBus.this;
    }

    boolean add(Publication publication) {
      Hint hint = publication.notification().topic().hint() ? Hint.of(publication.wire()) : null;
      if (hint != null && hints.containsKey(hint)) {
        return false;
      }
      if (publications.size() >= limits.queueCapacity()
          || publication.wire().byteLength() > limits.pendingBytes() - bytes) {
        throw new IllegalStateException("transaction notification budget exceeded");
      }
      publications.add(publication);
      bytes += publication.wire().byteLength();
      if (hint != null) {
        hints.put(hint, publication);
      }
      return true;
    }

    @Override
    public void afterCommit() {
      committed = true;
      for (Publication publication : publications) {
        router.local(publication.notification(), publication.wire().byteLength());
      }
    }

    @Override
    public void afterCompletion(int status) {
      publications.clear();
      hints.clear();
      bytes = 0;
    }
  }
}
