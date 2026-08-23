package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.spring.realtime.RealtimeEventSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * PostgreSQL realtime notification 的本地订阅与分发器。
 *
 * <p>本类不创建线程或连接。统一 LISTEN loop 把 payload 交给 {@link #onNotification(String)}，连接建立或重建后调用 {@link
 * #onResync()}。malformed/unknown payload 触发全部本地订阅 resync；合法 envelope 仅分发到对应 Thread。
 */
public final class PostgresqlRealtimeEventSource implements RealtimeEventSource {

  public static final String CHANNEL = PostgresqlRealtimeChannel.NAME;

  private static final Logger log = LoggerFactory.getLogger(PostgresqlRealtimeEventSource.class);

  private final RealtimeNotificationCodec notificationCodec;
  private final Map<UUID, List<Subscriber>> subscribersByThread = new HashMap<>();
  private final Object lifecycleFence = new Object();

  private boolean closed;

  public PostgresqlRealtimeEventSource(RealtimeNotificationCodec notificationCodec) {
    this.notificationCodec = Objects.requireNonNull(notificationCodec, "notificationCodec");
  }

  @Override
  public AutoCloseable subscribe(
      UUID threadId, Consumer<RealtimeEvent> onEvent, Runnable onResync) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(onEvent, "onEvent");
    Objects.requireNonNull(onResync, "onResync");
    Subscriber subscriber = new Subscriber(threadId, onEvent, onResync);
    synchronized (lifecycleFence) {
      if (closed) {
        throw new IllegalStateException("PostgresqlRealtimeEventSource is already closed");
      }
      subscribersByThread.computeIfAbsent(threadId, ignored -> new ArrayList<>()).add(subscriber);
    }
    return subscriber::close;
  }

  /** 由统一 PostgreSQL listener 交付一条 {@link #CHANNEL} notification。 */
  public void onNotification(String payload) {
    RealtimeNotificationCodec.Envelope envelope;
    try {
      envelope = notificationCodec.decode(payload);
    } catch (RuntimeException error) {
      log.warn("cannot decode realtime PostgreSQL notification; requesting resync", error);
      onResync();
      return;
    }
    synchronized (lifecycleFence) {
      if (closed) {
        return;
      }
      if (envelope instanceof RealtimeNotificationCodec.Envelope.Event event) {
        dispatchEvent(event.event());
      } else if (envelope instanceof RealtimeNotificationCodec.Envelope.Resync resync) {
        dispatchResync(resync.threadId());
      }
    }
  }

  /** 由统一 listener 在显式建立或重建连接后触发全部本地订阅恢复。 */
  public void onResync() {
    synchronized (lifecycleFence) {
      if (closed) {
        return;
      }
      for (List<Subscriber> subscribers : List.copyOf(subscribersByThread.values())) {
        for (Subscriber subscriber : List.copyOf(subscribers)) {
          subscriber.resync();
        }
      }
    }
  }

  @Override
  public void close() {
    synchronized (lifecycleFence) {
      if (closed) {
        return;
      }
      closed = true;
      for (List<Subscriber> subscribers : subscribersByThread.values()) {
        for (Subscriber subscriber : subscribers) {
          subscriber.closed = true;
        }
      }
      subscribersByThread.clear();
    }
  }

  private void dispatchEvent(RealtimeEvent event) {
    List<Subscriber> subscribers = subscribersByThread.get(event.threadId());
    if (subscribers == null) {
      return;
    }
    for (Subscriber subscriber : List.copyOf(subscribers)) {
      subscriber.event(event);
    }
  }

  private void dispatchResync(UUID threadId) {
    List<Subscriber> subscribers = subscribersByThread.get(threadId);
    if (subscribers == null) {
      return;
    }
    for (Subscriber subscriber : List.copyOf(subscribers)) {
      subscriber.resync();
    }
  }

  private final class Subscriber {

    private final UUID threadId;
    private final Consumer<RealtimeEvent> onEvent;
    private final Runnable onResync;
    private boolean closed;

    private Subscriber(UUID threadId, Consumer<RealtimeEvent> onEvent, Runnable onResync) {
      this.threadId = threadId;
      this.onEvent = onEvent;
      this.onResync = onResync;
    }

    private void event(RealtimeEvent event) {
      if (closed) {
        return;
      }
      try {
        onEvent.accept(event);
      } catch (RuntimeException error) {
        log.warn("realtime subscriber callback failed for threadId={}; skipping", threadId, error);
      }
    }

    private void resync() {
      if (closed) {
        return;
      }
      try {
        onResync.run();
      } catch (RuntimeException error) {
        log.warn("realtime resync callback failed for threadId={}; skipping", threadId, error);
      }
    }

    private void close() {
      synchronized (lifecycleFence) {
        if (closed) {
          return;
        }
        closed = true;
        List<Subscriber> subscribers = subscribersByThread.get(threadId);
        if (subscribers == null) {
          return;
        }
        subscribers.remove(this);
        if (subscribers.isEmpty()) {
          subscribersByThread.remove(threadId);
        }
      }
    }
  }
}
