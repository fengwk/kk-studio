package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

class PostgresqlWorkListenerTest {

  @Test
  void reconnectsThenListensWithAutoCommitAndWakesForNotifications() throws Exception {
    FakeConnection notPostgresql = new FakeConnection(false);
    FakeConnection listening = new FakeConnection(true);
    SequencedDataSource dataSource =
        new SequencedDataSource(
            () -> {
              throw new SQLException("connection unavailable");
            },
            notPostgresql::connection,
            listening::connection);
    AtomicInteger wakes = new AtomicInteger();
    PostgresqlWorkListener listener =
        new PostgresqlWorkListener(
            dataSource, wakes::incrementAndGet, Duration.ofMillis(10), Duration.ofMillis(1));

    listener.start();
    listener.start();
    assertTrue(listening.notificationDelivered.await(10, TimeUnit.SECONDS));
    await(() -> wakes.get() >= 2);
    listener.close();
    await(listening.closed::get);

    assertTrue(dataSource.connectionAttempts.get() >= 3);
    assertTrue(notPostgresql.closed.get());
    assertTrue(listening.autoCommit.get());
    assertTrue(listening.executedSql.contains("LISTEN " + PostgresqlWorkListener.CHANNEL));
    assertTrue(listening.executedSql.contains("UNLISTEN " + PostgresqlWorkListener.CHANNEL));
    assertEquals(10, listening.notificationPollMillis.get());
    assertFalse(listener.isRunning());
    assertThrows(IllegalStateException.class, listener::start);
  }

  @Test
  void stopInterruptsReconnectBackoffAndDefaultConstructorCanCloseBeforeStart() throws Exception {
    SequencedDataSource unavailable =
        new SequencedDataSource(
            () -> {
              throw new SQLException("still unavailable");
            });
    PostgresqlWorkListener listener =
        new PostgresqlWorkListener(
            unavailable, () -> {}, Duration.ofMillis(10), Duration.ofSeconds(10));
    listener.start();
    await(() -> unavailable.connectionAttempts.get() >= 1);
    listener.stop();
    listener.stop();
    int attemptsAfterStop = unavailable.connectionAttempts.get();
    Thread.sleep(100);
    assertEquals(attemptsAfterStop, unavailable.connectionAttempts.get());
    assertFalse(listener.isRunning());

    PostgresqlWorkListener neverStarted =
        new PostgresqlWorkListener(
            new SequencedDataSource(), () -> {}, Duration.ofMillis(10), Duration.ofSeconds(1));
    neverStarted.close();
    assertFalse(neverStarted.isRunning());
  }

  @Test
  void connectionReturningAfterStopIsClosedWithoutListenOrStartupWake() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FakeConnection lateConnection = new FakeConnection(true);
    SequencedDataSource dataSource =
        new SequencedDataSource(
            () -> {
              entered.countDown();
              boolean released = false;
              while (!released) {
                try {
                  release.await();
                  released = true;
                } catch (InterruptedException ignored) {
                  // 模拟一次 DataSource 调用：调用者被中断时也不应中止。
                }
              }
              return lateConnection.connection();
            });
    AtomicInteger wakes = new AtomicInteger();
    PostgresqlWorkListener listener =
        new PostgresqlWorkListener(
            dataSource, wakes::incrementAndGet, Duration.ofMillis(10), Duration.ofMillis(1));

    listener.start();
    assertTrue(entered.await(10, TimeUnit.SECONDS));
    listener.stop();
    release.countDown();
    await(lateConnection.closed::get);

    assertEquals(0, wakes.get());
    assertTrue(lateConnection.executedSql.isEmpty());
  }

  @Test
  void disconnectWithUnlistenFailureStillReconnects() throws Exception {
    FakeConnection disconnecting = new FakeConnection(true);
    disconnecting.disconnectAfterNotification.set(true);
    disconnecting.failUnlisten.set(true);
    FakeConnection replacement = new FakeConnection(true);
    SequencedDataSource dataSource =
        new SequencedDataSource(disconnecting::connection, replacement::connection);
    PostgresqlWorkListener listener =
        new PostgresqlWorkListener(
            dataSource, () -> {}, Duration.ofMillis(10), Duration.ofMillis(1));

    listener.start();
    assertTrue(disconnecting.unlistenFailed.await(10, TimeUnit.SECONDS));
    await(() -> dataSource.connectionAttempts.get() >= 2);
    listener.stop();
    await(replacement.closed::get);
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!check.done()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("condition was not met");
      }
      Thread.sleep(1);
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    if (type == char.class) {
      return '\0';
    }
    return null;
  }

  @FunctionalInterface
  private interface Check {
    boolean done();
  }

  @FunctionalInterface
  private interface ConnectionAttempt {
    Connection get() throws SQLException;
  }

  private static final class SequencedDataSource implements DataSource {

    private final Queue<ConnectionAttempt> attempts = new ArrayDeque<>();
    private final AtomicInteger connectionAttempts = new AtomicInteger();

    SequencedDataSource(ConnectionAttempt... attempts) {
      for (ConnectionAttempt attempt : attempts) {
        this.attempts.add(attempt);
      }
    }

    @Override
    public synchronized Connection getConnection() throws SQLException {
      connectionAttempts.incrementAndGet();
      ConnectionAttempt attempt = attempts.peek();
      if (attempts.size() > 1) {
        attempt = attempts.remove();
      }
      if (attempt == null) {
        throw new SQLException("no configured connection");
      }
      return attempt.get();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  private static final class FakeConnection {

    private final boolean postgresql;
    private final AtomicBoolean autoCommit = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CopyOnWriteArrayList<String> executedSql = new CopyOnWriteArrayList<>();
    private final AtomicInteger notificationPollMillis = new AtomicInteger();
    private final AtomicInteger notificationCalls = new AtomicInteger();
    private final AtomicBoolean disconnectAfterNotification = new AtomicBoolean();
    private final AtomicBoolean failUnlisten = new AtomicBoolean();
    private final CountDownLatch notificationDelivered = new CountDownLatch(1);
    private final CountDownLatch unlistenFailed = new CountDownLatch(1);
    private final CountDownLatch blockNotifications = new CountDownLatch(1);
    private final PGConnection pgConnection;
    private final Statement statement;
    private final Connection connection;

    FakeConnection(boolean postgresql) {
      this.postgresql = postgresql;
      this.pgConnection =
          (PGConnection)
              Proxy.newProxyInstance(
                  PGConnection.class.getClassLoader(),
                  new Class<?>[] {PGConnection.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("getNotifications") && args != null) {
                      notificationPollMillis.set((Integer) args[0]);
                      int call = notificationCalls.incrementAndGet();
                      if (call == 1) {
                        return null;
                      }
                      if (call == 2) {
                        return new PGNotification[0];
                      }
                      if (call == 3) {
                        notificationDelivered.countDown();
                        return new PGNotification[] {notification()};
                      }
                      if (disconnectAfterNotification.get()) {
                        throw new SQLException("notification connection lost");
                      }
                      try {
                        blockNotifications.await();
                        return null;
                      } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("notification poll interrupted", error);
                      }
                    }
                    return defaultValue(method.getReturnType());
                  });
      this.statement =
          (Statement)
              Proxy.newProxyInstance(
                  Statement.class.getClassLoader(),
                  new Class<?>[] {Statement.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("execute")) {
                      String sql = (String) args[0];
                      executedSql.add(sql);
                      if (sql.startsWith("UNLISTEN") && failUnlisten.get()) {
                        unlistenFailed.countDown();
                        throw new SQLException("cannot unlisten");
                      }
                      return true;
                    }
                    return defaultValue(method.getReturnType());
                  });
      this.connection =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    return switch (method.getName()) {
                      case "setAutoCommit" -> {
                        autoCommit.set((Boolean) args[0]);
                        yield null;
                      }
                      case "isWrapperFor" -> postgresql && args[0] == PGConnection.class;
                      case "unwrap" -> {
                        if (postgresql && args[0] == PGConnection.class) {
                          yield pgConnection;
                        }
                        throw new SQLException("not a PostgreSQL wrapper");
                      }
                      case "createStatement" -> statement;
                      case "close" -> {
                        closed.set(true);
                        blockNotifications.countDown();
                        yield null;
                      }
                      case "isClosed" -> closed.get();
                      default -> defaultValue(method.getReturnType());
                    };
                  });
    }

    Connection connection() {
      return connection;
    }

    private static PGNotification notification() {
      return new PGNotification() {
        @Override
        public String getName() {
          return PostgresqlWorkListener.CHANNEL;
        }

        @Override
        public int getPID() {
          return 1;
        }

        @Override
        public String getParameter() {
          return "";
        }
      };
    }
  }
}
