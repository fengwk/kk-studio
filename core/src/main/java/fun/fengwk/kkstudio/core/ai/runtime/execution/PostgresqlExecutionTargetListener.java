package fun.fengwk.kkstudio.core.ai.runtime.execution;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-process PostgreSQL {@code LISTEN harness_execution_target} loop.
 *
 * <p>One daemon thread holds a long-lived {@code PGConnection} and issues {@code LISTEN
 * harness_execution_target} on every (re)connect. While the connection is healthy, the thread
 * blocks inside {@link PGConnection#getNotifications(int)} for up to {@link #pollMillis}; this
 * single blocking call replaces the previous polling loop and eliminates the per-tick {@code SELECT
 * 1} keep-alive.
 *
 * <p>On each {@code NOTIFY} the dispatcher is woken. On connect (and every reconnect) the
 * dispatcher is woken once so any notifications lost during the gap are recovered by the
 * dispatcher's startup drain. On any {@link SQLException} the connection is dropped and the thread
 * sleeps {@link #reconnectBackoffMillis} before reconnecting. Stop is idempotent.
 *
 * <p>Delivery is best-effort and lossy at the NOTIFY layer; the durable state in {@code
 * harness_execution_target} is the source of truth and the dispatcher recovers missed wakes through
 * the nearest-due timer.
 */
@Slf4j
public final class PostgresqlExecutionTargetListener {

  /** PostgreSQL channel name. */
  public static final String CHANNEL = "harness_execution_target";

  private final DataSource dataSource;
  private final PostgresqlExecutionTargetDispatcher dispatcher;
  private final long pollMillis;
  private final long reconnectBackoffMillis;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Thread loopThread;

  public PostgresqlExecutionTargetListener(
      DataSource dataSource, PostgresqlExecutionTargetDispatcher dispatcher) {
    this(dataSource, dispatcher, 5_000L, 1_000L);
  }

  /** Test seam: inject poll cadence and reconnect backoff. */
  PostgresqlExecutionTargetListener(
      DataSource dataSource,
      PostgresqlExecutionTargetDispatcher dispatcher,
      long pollMillis,
      long reconnectBackoffMillis) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    if (pollMillis <= 0) {
      throw new IllegalArgumentException("pollMillis must be positive");
    }
    if (reconnectBackoffMillis <= 0) {
      throw new IllegalArgumentException("reconnectBackoffMillis must be positive");
    }
    this.pollMillis = pollMillis;
    this.reconnectBackoffMillis = reconnectBackoffMillis;
  }

  /** Start the listener loop. Idempotent. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    Thread t = new Thread(this::loop, "execution-target-listen");
    t.setDaemon(true);
    loopThread = t;
    t.start();
  }

  /** Stop the listener loop. Idempotent. */
  public void stop() {
    running.set(false);
    Thread t = loopThread;
    if (t != null) {
      t.interrupt();
    }
  }

  private void loop() {
    while (running.get()) {
      try (Connection conn = dataSource.getConnection()) {
        if (!conn.isWrapperFor(PGConnection.class)) {
          throw new SQLException(
              "datasource connection cannot unwrap PGConnection: " + conn.getClass());
        }
        PGConnection pgConn = conn.unwrap(PGConnection.class);
        try (Statement stmt = conn.createStatement()) {
          stmt.execute("LISTEN " + CHANNEL);
        }
        // Recover whatever changed during the gap between reconnects.
        dispatcher.wake();
        pollUntilStop(pgConn);
      } catch (SQLException | RuntimeException error) {
        if (!running.get()) {
          return;
        }
        log.warn("execution target LISTEN connection lost; reconnecting", error);
        sleep(reconnectBackoffMillis);
      }
    }
  }

  private void pollUntilStop(PGConnection pgConn) throws SQLException {
    while (running.get()) {
      PGNotification[] notes = pgConn.getNotifications((int) pollMillis);
      if (!running.get()) {
        return;
      }
      if (notes != null && notes.length > 0) {
        dispatcher.wake();
      }
    }
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }
}
