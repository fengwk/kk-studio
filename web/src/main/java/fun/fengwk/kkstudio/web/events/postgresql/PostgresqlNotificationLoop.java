package fun.fengwk.kkstudio.web.events.postgresql;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.SmartLifecycle;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Web 进程共享的 PostgreSQL notification loop。
 *
 * <p>Loop 在一个 daemon platform thread 上持有一个专用 JDBC connection，并一次性 LISTEN 构造时提供的固定 channel
 * 集合。连接建立且全部 LISTEN 完成后逐 handler resync；连接丢失后退避重连。handler 失败彼此隔离，不会终止连接循环。
 */
@Slf4j
public final class PostgresqlNotificationLoop implements SmartLifecycle, AutoCloseable {

  private static final long SHUTDOWN_JOIN_MILLIS = 5_000;

  private final DataSource dataSource;
  private final Map<String, PostgresqlNotificationHandler> handlers;
  private final int notificationPollMillis;
  private final long reconnectBackoffMillis;
  private final Object lifecycleLock = new Object();

  private volatile boolean running;
  private volatile boolean closed;
  private volatile Thread loopThread;
  private volatile Connection activeConnection;

  public PostgresqlNotificationLoop(
      DataSource dataSource,
      Collection<PostgresqlNotificationHandler> handlers,
      Duration notificationPollInterval,
      Duration reconnectBackoff) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.handlers = indexHandlers(handlers);
    this.notificationPollMillis =
        requirePositiveWholeMillisInt(notificationPollInterval, "notificationPollInterval");
    this.reconnectBackoffMillis = requirePositiveWholeMillis(reconnectBackoff, "reconnectBackoff");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("notification loop is already closed");
      }
      if (running) {
        return;
      }
      running = true;
      Thread thread =
          Thread.ofPlatform()
              .name("postgresql-notification-loop")
              .daemon(true)
              .unstarted(this::runLoop);
      loopThread = thread;
      try {
        thread.start();
      } catch (RuntimeException | Error error) {
        running = false;
        loopThread = null;
        throw error;
      }
    }
  }

  @Override
  public void stop() {
    closeInternal(false);
  }

  @Override
  public void stop(Runnable callback) {
    try {
      closeInternal(false);
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void close() {
    closeInternal(true);
  }

  private void closeInternal(boolean permanentClose) {
    Connection connection;
    Thread thread;
    synchronized (lifecycleLock) {
      if (closed || (!running && !permanentClose)) {
        return;
      }
      if (permanentClose) {
        closed = true;
      }
      running = false;
      connection = activeConnection;
      thread = loopThread;
    }
    abortConnection(connection);
    if (thread != null) {
      thread.interrupt();
      join(thread);
    }
  }

  private void runLoop() {
    try {
      while (running) {
        listenUntilDisconnected();
      }
    } finally {
      synchronized (lifecycleLock) {
        running = false;
        loopThread = null;
      }
    }
  }

  private void listenUntilDisconnected() {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      listenOnConnection(connection);
    } catch (SQLException | RuntimeException error) {
      if (running) {
        log.warn("PostgreSQL notification connection lost; reconnecting", error);
        sleepBeforeReconnect();
      }
    } finally {
      clearConnection(connection);
    }
  }

  private void listenOnConnection(Connection connection) throws SQLException {
    try (connection) {
      connection.setAutoCommit(true);
      if (!installConnection(connection)) {
        return;
      }
      if (!connection.isWrapperFor(PGConnection.class)) {
        throw new SQLException(
            "datasource connection cannot unwrap PGConnection: " + connection.getClass());
      }
      PGConnection pgConnection = connection.unwrap(PGConnection.class);
      listen(connection);
      if (!running) {
        return;
      }
      handlers.values().forEach(this::resync);
      while (running) {
        PGNotification[] notifications = pgConnection.getNotifications(notificationPollMillis);
        if (!running) {
          return;
        }
        if (notifications != null) {
          for (PGNotification notification : notifications) {
            if (!running) {
              return;
            }
            PostgresqlNotificationHandler handler = handlers.get(notification.getName());
            if (handler != null) {
              notify(handler, notification.getParameter());
            }
          }
        }
      }
    }
  }

  private boolean installConnection(Connection connection) {
    synchronized (lifecycleLock) {
      if (!running) {
        return false;
      }
      activeConnection = connection;
      return true;
    }
  }

  private void clearConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    synchronized (lifecycleLock) {
      if (activeConnection == connection) {
        activeConnection = null;
      }
    }
  }

  private void listen(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String channel : handlers.keySet()) {
        statement.execute("LISTEN " + channel);
      }
    }
  }

  private void resync(PostgresqlNotificationHandler handler) {
    if (!running) {
      return;
    }
    try {
      handler.onResync();
    } catch (RuntimeException error) {
      log.warn(
          "PostgreSQL notification resync handler failed channel={}; skipping",
          handler.channel(),
          error);
    }
  }

  private void notify(PostgresqlNotificationHandler handler, String payload) {
    try {
      handler.onNotification(payload);
    } catch (RuntimeException error) {
      log.warn(
          "PostgreSQL notification handler failed channel={}; skipping", handler.channel(), error);
    }
  }

  private void sleepBeforeReconnect() {
    try {
      Thread.sleep(reconnectBackoffMillis);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private static void abortConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException error) {
      log.warn(
          "cannot abort PostgreSQL notification connection; continuing with interrupt and bounded join",
          error);
    }
  }

  private static void join(Thread thread) {
    if (thread == Thread.currentThread()) {
      return;
    }
    try {
      thread.join(SHUTDOWN_JOIN_MILLIS);
      if (thread.isAlive()) {
        log.warn("PostgreSQL notification loop did not stop within {}ms", SHUTDOWN_JOIN_MILLIS);
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private static Map<String, PostgresqlNotificationHandler> indexHandlers(
      Collection<PostgresqlNotificationHandler> handlers) {
    Objects.requireNonNull(handlers, "handlers");
    if (handlers.isEmpty()) {
      throw new IllegalArgumentException("handlers must not be empty");
    }
    Map<String, PostgresqlNotificationHandler> indexed = new LinkedHashMap<>();
    for (PostgresqlNotificationHandler handler : handlers) {
      Objects.requireNonNull(handler, "handler");
      if (indexed.putIfAbsent(handler.channel(), handler) != null) {
        throw new IllegalArgumentException(
            "duplicate PostgreSQL notification channel: " + handler.channel());
      }
    }
    return Collections.unmodifiableMap(indexed);
  }

  private static int requirePositiveWholeMillisInt(Duration value, String name) {
    long millis = requirePositiveWholeMillis(value, name);
    if (millis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(name + " must not exceed " + Integer.MAX_VALUE + "ms");
    }
    return (int) millis;
  }

  private static long requirePositiveWholeMillis(Duration value, String name) {
    Objects.requireNonNull(value, name);
    long nanos;
    try {
      nanos = value.toNanos();
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException(name + " is too large", error);
    }
    if (nanos <= 0 || nanos % 1_000_000 != 0) {
      throw new IllegalArgumentException(name + " must be a positive whole-millisecond duration");
    }
    return nanos / 1_000_000;
  }
}
