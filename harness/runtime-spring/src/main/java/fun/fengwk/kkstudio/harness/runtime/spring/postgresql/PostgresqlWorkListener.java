package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

/**
 * Dedicated PostgreSQL {@code LISTEN} loop that turns Work notifications into dispatcher wake
 * hints.
 *
 * <p>Notifications are not correctness facts: every successful connection performs a startup wake,
 * and the dispatcher retains its independent periodic poll. The listener owns only its daemon
 * thread and dedicated JDBC connection; it does not own the dispatcher or any injected executor.
 */
public final class PostgresqlWorkListener implements AutoCloseable {

  public static final String CHANNEL = PostgresqlWorkChannel.NAME;

  private static final Logger log = LoggerFactory.getLogger(PostgresqlWorkListener.class);
  private static final Duration DEFAULT_NOTIFICATION_POLL = Duration.ofSeconds(5);
  private static final Duration DEFAULT_RECONNECT_BACKOFF = Duration.ofSeconds(1);

  private final DataSource dataSource;
  private final Runnable wake;
  private final int notificationPollMillis;
  private final long reconnectBackoffMillis;
  private final Object lifecycleLock = new Object();

  private volatile boolean running;
  private volatile boolean stopped;
  private volatile Thread loopThread;

  public PostgresqlWorkListener(DataSource dataSource, Runnable wake) {
    this(dataSource, wake, DEFAULT_NOTIFICATION_POLL, DEFAULT_RECONNECT_BACKOFF);
  }

  public PostgresqlWorkListener(
      DataSource dataSource,
      Runnable wake,
      Duration notificationPollInterval,
      Duration reconnectBackoff) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.wake = Objects.requireNonNull(wake, "wake");
    this.notificationPollMillis =
        requirePositiveWholeMillisInt(notificationPollInterval, "notificationPollInterval");
    this.reconnectBackoffMillis = requirePositiveWholeMillis(reconnectBackoff, "reconnectBackoff");
  }

  /** Starts the one-shot listener lifecycle; repeated calls while running are no-ops. */
  public void start() {
    synchronized (lifecycleLock) {
      if (stopped) {
        throw new IllegalStateException("listener is already stopped");
      }
      if (running) {
        return;
      }
      running = true;
      Thread thread = new Thread(this::loop, "harness-work-listen");
      thread.setDaemon(true);
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

  /** Stops reconnecting and interrupts the daemon loop to shorten a blocking poll/backoff. */
  public void stop() {
    synchronized (lifecycleLock) {
      if (stopped) {
        return;
      }
      stopped = true;
      running = false;
      Thread thread = loopThread;
      if (thread != null) {
        thread.interrupt();
      }
    }
  }

  public boolean isRunning() {
    return running;
  }

  @Override
  public void close() {
    stop();
  }

  private void loop() {
    try {
      while (running) {
        listenUntilDisconnected();
      }
    } finally {
      running = false;
      loopThread = null;
    }
  }

  private void listenUntilDisconnected() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      if (!running) {
        return;
      }
      if (!connection.isWrapperFor(PGConnection.class)) {
        throw new SQLException(
            "datasource connection cannot unwrap PGConnection: " + connection.getClass());
      }
      PGConnection pgConnection = connection.unwrap(PGConnection.class);
      try (Statement statement = connection.createStatement()) {
        statement.execute("LISTEN " + CHANNEL);
      }
      try {
        if (!running) {
          return;
        }
        wake.run();
        while (running) {
          PGNotification[] notifications = pgConnection.getNotifications(notificationPollMillis);
          if (!running) {
            return;
          }
          if (notifications != null && notifications.length > 0) {
            wake.run();
          }
        }
      } finally {
        unlisten(connection);
      }
    } catch (SQLException | RuntimeException error) {
      if (!running) {
        return;
      }
      log.warn("Harness Work LISTEN connection lost; reconnecting", error);
      sleepBeforeReconnect();
    }
  }

  private void sleepBeforeReconnect() {
    try {
      Thread.sleep(reconnectBackoffMillis);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void unlisten(Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.execute("UNLISTEN " + CHANNEL);
    } catch (SQLException error) {
      if (running) {
        log.debug("cannot UNLISTEN Harness Work channel on a closing connection", error);
      }
    }
  }

  private static int requirePositiveWholeMillisInt(Duration value, String name) {
    long millis = requirePositiveWholeMillis(value, name);
    if (millis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(name + " must not exceed " + Integer.MAX_VALUE + "ms");
    }
    return (int) millis;
  }

  private static long requirePositiveWholeMillis(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name).toMillis();
  }
}
