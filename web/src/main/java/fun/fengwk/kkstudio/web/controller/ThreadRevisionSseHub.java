package fun.fengwk.kkstudio.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Process-local fan-out for PostgreSQL Thread revision invalidations.
 *
 * <p>LISTEN/NOTIFY is intentionally only a wake-up. On notification this hub reads the current
 * durable revision before notifying SSE subscribers; a successful LISTEN startup/reconnect emits a
 * resync signal because notifications during a disconnected gap are irrecoverable.
 */
@Slf4j
@Component
final class ThreadRevisionSseHub implements SmartLifecycle, ThreadRevisionEventSource {
  static final String CHANNEL = "harness_thread_revision";

  private final DataSource dataSource;
  private final Map<Long, Set<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();
  private volatile boolean running;
  private volatile Thread listenerThread;

  ThreadRevisionSseHub(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public AutoCloseable subscribe(long threadId, Consumer<Event> consumer) {
    subscribers.computeIfAbsent(threadId, ignored -> new CopyOnWriteArraySet<>()).add(consumer);
    consumer.accept(new Event(null, true));
    return () -> {
      Set<Consumer<Event>> threadSubscribers = subscribers.get(threadId);
      if (threadSubscribers != null) {
        threadSubscribers.remove(consumer);
        if (threadSubscribers.isEmpty()) {
          subscribers.remove(threadId, threadSubscribers);
        }
      }
    };
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
    long threadId;
    try {
      threadId = Long.parseLong(rawThreadId);
    } catch (NumberFormatException ignored) {
      return;
    }
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "select revision from harness_thread where id = " + Long.toString(threadId))) {
      if (result.next()) {
        publish(threadId, new Event(result.getString(1), false));
      }
    }
  }

  private void broadcastResync() {
    subscribers.forEach((threadId, ignored) -> publish(threadId, new Event(null, true)));
  }

  private void publish(long threadId, Event event) {
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
