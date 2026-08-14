package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * PostgreSQL Thread revision 失效通知的进程内 fan-out。
 *
 * <p>LISTEN/NOTIFY 故意只作为唤醒信号。收到通知后，本 hub 先读取当前持久 revision 再通知订阅者；LISTEN 启动/重连成功时广播
 * resync，因为断连期间的通知不可恢复。{@link #subscribe} 先注册 consumer 再读当前 revision 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
 */
@Slf4j
@Component
final class ThreadRevisionHub implements SmartLifecycle, ThreadRevisionEventSource {

  static final String CHANNEL = "harness_thread_revision";

  private final DataSource dataSource;
  private final Map<UUID, Set<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();
  private volatile boolean running;
  private volatile Thread listenerThread;

  ThreadRevisionHub(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public SourceSubscribed subscribe(UUID threadId, Consumer<Event> consumer) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(consumer, "consumer");
    Set<Consumer<Event>> threadSubscribers =
        subscribers.computeIfAbsent(threadId, ignored -> new CopyOnWriteArraySet<>());
    threadSubscribers.add(consumer);
    try {
      long cursor = currentRevision(threadId);
      return new SourceSubscribed(
          cursor,
          () -> {
            Set<Consumer<Event>> currentSubscribers = subscribers.get(threadId);
            if (currentSubscribers != null) {
              currentSubscribers.remove(consumer);
              if (currentSubscribers.isEmpty()) {
                subscribers.remove(threadId, currentSubscribers);
              }
            }
          });
    } catch (RuntimeException error) {
      threadSubscribers.remove(consumer);
      if (threadSubscribers.isEmpty()) {
        subscribers.remove(threadId, threadSubscribers);
      }
      throw error;
    }
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    Thread thread = new Thread(this::listen, "thread-revision-listen");
    thread.setDaemon(true);
    listenerThread = thread;
    thread.start();
  }

  @Override
  public void stop() {
    running = false;
    Thread thread = listenerThread;
    if (thread != null) {
      thread.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MIN_VALUE;
  }

  private void listen() {
    while (running) {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        statement.execute("LISTEN " + CHANNEL);
        broadcastResync();
        while (running) {
          PGNotification[] notifications = pgConnection.getNotifications(5_000);
          if (notifications != null) {
            for (PGNotification notification : notifications) {
              publishCurrentRevision(connection, notification.getParameter());
            }
          }
        }
      } catch (SQLException | RuntimeException error) {
        if (running) {
          log.warn("thread revision LISTEN connection lost; reconnecting", error);
          sleep();
        }
      }
    }
  }

  private void publishCurrentRevision(Connection connection, String rawThreadId)
      throws SQLException {
    UUID threadId;
    try {
      threadId = UUID.fromString(rawThreadId);
    } catch (IllegalArgumentException ignored) {
      return;
    }
    try (PreparedStatement statement =
        connection.prepareStatement("select revision from harness_thread where id = ?")) {
      statement.setObject(1, threadId);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          publish(threadId, new Event(result.getString(1), false));
        }
      }
    }
  }

  private long currentRevision(UUID threadId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select revision from harness_thread where id = ?")) {
      statement.setObject(1, threadId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalArgumentException("unknown thread: " + threadId);
        }
        return result.getLong(1);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("cannot read current thread revision", error);
    }
  }

  void broadcastResync() {
    subscribers.forEach((threadId, ignored) -> publish(threadId, new Event(null, true)));
  }

  private void publish(UUID threadId, Event event) {
    Set<Consumer<Event>> threadSubscribers = subscribers.get(threadId);
    if (threadSubscribers != null) {
      threadSubscribers.forEach(consumer -> consumer.accept(event));
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(1_000);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }
}
