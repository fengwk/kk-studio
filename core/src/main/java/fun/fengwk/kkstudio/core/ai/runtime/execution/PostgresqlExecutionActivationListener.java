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

/** 单进程 PostgreSQL {@code LISTEN harness_execution_activation} 循环。 */
@Slf4j
public final class PostgresqlExecutionActivationListener {

  /** PostgreSQL NOTIFY 通道。 */
  public static final String CHANNEL = "harness_execution_activation";

  private final DataSource dataSource;
  private final PostgresqlExecutionActivationDispatcher dispatcher;
  private final long pollMillis;
  private final long reconnectBackoffMillis;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Thread loopThread;

  public PostgresqlExecutionActivationListener(
      DataSource dataSource, PostgresqlExecutionActivationDispatcher dispatcher) {
    this(dataSource, dispatcher, 5_000L, 1_000L);
  }

  /** 测试注入轮询和重连间隔。 */
  PostgresqlExecutionActivationListener(
      DataSource dataSource,
      PostgresqlExecutionActivationDispatcher dispatcher,
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

  /** 启动监听，重复调用无效。 */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    Thread thread = new Thread(this::loop, "execution-activation-listen");
    thread.setDaemon(true);
    loopThread = thread;
    thread.start();
  }

  /** 停止监听，重复调用无效。 */
  public void stop() {
    running.set(false);
    Thread thread = loopThread;
    if (thread != null) {
      thread.interrupt();
    }
  }

  private void loop() {
    while (running.get()) {
      try (Connection connection = dataSource.getConnection()) {
        if (!connection.isWrapperFor(PGConnection.class)) {
          throw new SQLException(
              "datasource connection cannot unwrap PGConnection: " + connection.getClass());
        }
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        try (Statement statement = connection.createStatement()) {
          statement.execute("LISTEN " + CHANNEL);
        }
        dispatcher.wake();
        pollUntilStop(pgConnection);
      } catch (SQLException | RuntimeException error) {
        if (!running.get()) {
          return;
        }
        log.warn("execution activation LISTEN connection lost; reconnecting", error);
        sleep(reconnectBackoffMillis);
      }
    }
  }

  private void pollUntilStop(PGConnection connection) throws SQLException {
    while (running.get()) {
      PGNotification[] notifications = connection.getNotifications((int) pollMillis);
      if (!running.get()) {
        return;
      }
      if (notifications != null && notifications.length > 0) {
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
