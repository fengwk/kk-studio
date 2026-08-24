package fun.fengwk.kkstudio.web.events.postgresql;

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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

class PostgresqlNotificationLoopTest {

  private static final String WORK_CHANNEL = "harness_runtime_work";
  private static final String CANVAS_FUNCTION_WORK_CHANNEL = "canvas_function_work";
  private static final String THREAD_CHANNEL = "harness_thread_version";
  private static final String CANVAS_CHANNEL = "canvas_version";

  @Test
  void oneConnectionListensToAllChannelsAndIsolatesResyncAndNotificationFailures()
      throws Exception {
    FakeConnection connection = new FakeConnection();
    connection.notifications.add(
        new PGNotification[] {
          notification(WORK_CHANNEL, ""),
          notification(CANVAS_FUNCTION_WORK_CHANNEL, ""),
          notification(THREAD_CHANNEL, "thread:1"),
          notification(CANVAS_CHANNEL, "canvas:1")
        });
    SequencedDataSource dataSource = new SequencedDataSource(connection::connection);
    AtomicInteger workNotifications = new AtomicInteger();
    AtomicInteger canvasFunctionWorkNotifications = new AtomicInteger();
    AtomicInteger threadNotifications = new AtomicInteger();
    AtomicInteger canvasNotifications = new AtomicInteger();
    AtomicInteger threadResyncs = new AtomicInteger();
    AtomicInteger canvasResyncs = new AtomicInteger();
    PostgresqlNotificationLoop loop =
        new PostgresqlNotificationLoop(
            dataSource,
            List.of(
                new PostgresqlNotificationHandler(
                    WORK_CHANNEL,
                    ignored -> {
                      workNotifications.incrementAndGet();
                      throw new IllegalStateException("injected work handler failure");
                    },
                    () -> {
                      throw new IllegalStateException("injected work resync failure");
                    }),
                new PostgresqlNotificationHandler(
                    CANVAS_FUNCTION_WORK_CHANNEL,
                    ignored -> canvasFunctionWorkNotifications.incrementAndGet(),
                    () -> {}),
                new PostgresqlNotificationHandler(
                    THREAD_CHANNEL,
                    ignored -> threadNotifications.incrementAndGet(),
                    threadResyncs::incrementAndGet),
                new PostgresqlNotificationHandler(
                    CANVAS_CHANNEL,
                    ignored -> canvasNotifications.incrementAndGet(),
                    canvasResyncs::incrementAndGet)),
            Duration.ofMillis(10),
            Duration.ofMillis(1));

    loop.start();
    loop.start();
    assertTrue(connection.notificationDelivered.await(5, TimeUnit.SECONDS));
    await(() -> canvasNotifications.get() == 1);
    loop.close();

    // 三个 LISTEN 在同一个 connection 上完成；失败 handler 不阻断后续 resync 或通知。
    assertEquals(1, dataSource.connectionAttempts.get());
    assertEquals(
        List.of(
            "LISTEN " + WORK_CHANNEL,
            "LISTEN " + CANVAS_FUNCTION_WORK_CHANNEL,
            "LISTEN " + THREAD_CHANNEL,
            "LISTEN " + CANVAS_CHANNEL),
        connection.executedSql);
    assertTrue(connection.autoCommit.get());
    assertEquals(10, connection.notificationPollMillis.get());
    assertEquals(1, workNotifications.get());
    assertEquals(1, canvasFunctionWorkNotifications.get());
    assertEquals(1, threadNotifications.get());
    assertEquals(1, canvasNotifications.get());
    assertEquals(1, threadResyncs.get());
    assertEquals(1, canvasResyncs.get());
    assertTrue(connection.loopThread.get().isDaemon());
    assertFalse(connection.loopThread.get().isVirtual());
  }

  @Test
  void reconnectRunsResyncAgainAfterAllChannelsAreRegistered() throws Exception {
    FakeConnection disconnected = new FakeConnection();
    disconnected.failNotificationPoll.set(true);
    FakeConnection reconnected = new FakeConnection();
    SequencedDataSource dataSource =
        new SequencedDataSource(disconnected::connection, reconnected::connection);
    AtomicInteger resyncs = new AtomicInteger();
    PostgresqlNotificationLoop loop =
        new PostgresqlNotificationLoop(
            dataSource,
            handlers(ignored -> {}, resyncs::incrementAndGet),
            Duration.ofMillis(10),
            Duration.ofMillis(1));

    loop.start();
    assertTrue(reconnected.notificationPollEntered.await(5, TimeUnit.SECONDS));
    await(() -> resyncs.get() == 6);
    loop.close();

    // 三个 handler 在首次连接和重连成功后各 resync 一次。
    assertEquals(2, dataSource.connectionAttempts.get());
    assertEquals(3, disconnected.executedSql.size());
    assertEquals(3, reconnected.executedSql.size());
  }

  @Test
  void closeUnblocksPollAndDropsNotificationReturnedAfterShutdown() throws Exception {
    FakeConnection connection = new FakeConnection();
    connection.notificationOnClose = notification(WORK_CHANNEL, "late");
    AtomicInteger notifications = new AtomicInteger();
    AtomicInteger resyncs = new AtomicInteger();
    PostgresqlNotificationLoop loop =
        new PostgresqlNotificationLoop(
            new SequencedDataSource(connection::connection),
            handlers(ignored -> notifications.incrementAndGet(), resyncs::incrementAndGet),
            Duration.ofSeconds(5),
            Duration.ofSeconds(1));

    loop.start();
    assertTrue(connection.notificationPollEntered.await(5, TimeUnit.SECONDS));
    assertEquals(3, resyncs.get());

    loop.close();
    loop.close();

    // close 先撤销 running，再关闭 connection/interrupt/join；poll 竞态返回的通知不得进入 callback。
    assertTrue(connection.closed.get());
    assertTrue(connection.notificationPollReturned.await(5, TimeUnit.SECONDS));
    assertEquals(0, notifications.get());
    assertFalse(loop.isRunning());
    assertThrows(IllegalStateException.class, loop::start);
  }

  @Test
  void rejectsUnsafeOrDuplicateChannelsAndInvalidIntervals() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PostgresqlNotificationHandler("valid;notify evil", ignored -> {}, () -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PostgresqlNotificationHandler("Uppercase", ignored -> {}, () -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PostgresqlNotificationHandler("a".repeat(64), ignored -> {}, () -> {}));

    PostgresqlNotificationHandler duplicate =
        new PostgresqlNotificationHandler(WORK_CHANNEL, ignored -> {}, () -> {});
    DataSource dataSource = new SequencedDataSource();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlNotificationLoop(
                dataSource, List.of(), Duration.ofMillis(1), Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlNotificationLoop(
                dataSource,
                List.of(duplicate, duplicate),
                Duration.ofMillis(1),
                Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlNotificationLoop(
                dataSource, List.of(duplicate), Duration.ZERO, Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlNotificationLoop(
                dataSource, List.of(duplicate), Duration.ofNanos(1_500_000), Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlNotificationLoop(
                dataSource,
                List.of(duplicate),
                Duration.ofMillis((long) Integer.MAX_VALUE + 1),
                Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlNotificationLoop(
                dataSource,
                List.of(duplicate),
                Duration.ofMillis(1),
                Duration.ofSeconds(Long.MAX_VALUE)));
  }

  @Test
  void lifecycleMethodsAreIdempotentAndStopCallbackAlwaysRuns() {
    PostgresqlNotificationHandler handler =
        new PostgresqlNotificationHandler(WORK_CHANNEL, ignored -> {}, () -> {});
    PostgresqlNotificationLoop stopped =
        new PostgresqlNotificationLoop(
            new SequencedDataSource(),
            List.of(handler),
            Duration.ofMillis(1),
            Duration.ofMillis(1));
    AtomicBoolean callbackRan = new AtomicBoolean();

    stopped.stop();
    stopped.stop(() -> callbackRan.set(true));

    assertTrue(callbackRan.get());
    assertEquals(Integer.MAX_VALUE, stopped.getPhase());
    assertFalse(stopped.isRunning());
  }

  @Test
  void unsupportedConnectionIsClosedAndStopInterruptsReconnectBackoff() throws Exception {
    FakeConnection unsupported = new FakeConnection();
    unsupported.postgresql.set(false);
    PostgresqlNotificationLoop loop =
        new PostgresqlNotificationLoop(
            new SequencedDataSource(unsupported::connection),
            handlers(ignored -> {}, () -> {}),
            Duration.ofMillis(10),
            Duration.ofSeconds(10));

    loop.start();
    assertTrue(unsupported.closedLatch.await(5, TimeUnit.SECONDS));
    loop.stop();

    assertTrue(unsupported.closed.get());
    assertFalse(loop.isRunning());
  }

  private static List<PostgresqlNotificationHandler> handlers(
      Consumer<String> notificationCallback, Runnable resyncCallback) {
    return List.of(
        new PostgresqlNotificationHandler(WORK_CHANNEL, notificationCallback, resyncCallback),
        new PostgresqlNotificationHandler(THREAD_CHANNEL, notificationCallback, resyncCallback),
        new PostgresqlNotificationHandler(CANVAS_CHANNEL, notificationCallback, resyncCallback));
  }

  private static PGNotification notification(String channel, String payload) {
    return new PGNotification() {
      @Override
      public String getName() {
        return channel;
      }

      @Override
      public int getPID() {
        return 1;
      }

      @Override
      public String getParameter() {
        return payload;
      }
    };
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
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
      this.attempts.addAll(List.of(attempts));
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

    private final Queue<PGNotification[]> notifications = new ArrayDeque<>();
    private final CopyOnWriteArrayList<String> executedSql = new CopyOnWriteArrayList<>();
    private final AtomicBoolean autoCommit = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean postgresql = new AtomicBoolean(true);
    private final AtomicBoolean failNotificationPoll = new AtomicBoolean();
    private final AtomicBoolean notificationOnCloseReturned = new AtomicBoolean();
    private final AtomicInteger notificationPollMillis = new AtomicInteger();
    private final AtomicReference<Thread> loopThread = new AtomicReference<>();
    private final CountDownLatch notificationDelivered = new CountDownLatch(1);
    private final CountDownLatch notificationPollEntered = new CountDownLatch(1);
    private final CountDownLatch notificationPollReturned = new CountDownLatch(1);
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final CountDownLatch closePoll = new CountDownLatch(1);
    private final PGConnection pgConnection;
    private final Statement statement;
    private final Connection connection;
    private volatile PGNotification notificationOnClose;

    FakeConnection() {
      pgConnection =
          (PGConnection)
              Proxy.newProxyInstance(
                  PGConnection.class.getClassLoader(),
                  new Class<?>[] {PGConnection.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("getNotifications")) {
                      loopThread.compareAndSet(null, Thread.currentThread());
                      notificationPollMillis.set((Integer) arguments[0]);
                      notificationPollEntered.countDown();
                      if (failNotificationPoll.compareAndSet(true, false)) {
                        throw new SQLException("injected disconnect");
                      }
                      PGNotification[] batch = notifications.poll();
                      if (batch != null) {
                        notificationDelivered.countDown();
                        return batch;
                      }
                      try {
                        closePoll.await();
                      } catch (InterruptedException error) {
                        if (notificationOnClose == null) {
                          Thread.currentThread().interrupt();
                          throw new SQLException("notification poll interrupted", error);
                        }
                      }
                      notificationPollReturned.countDown();
                      if (notificationOnClose != null
                          && notificationOnCloseReturned.compareAndSet(false, true)) {
                        return new PGNotification[] {notificationOnClose};
                      }
                      return null;
                    }
                    return defaultValue(method.getReturnType());
                  });
      statement =
          (Statement)
              Proxy.newProxyInstance(
                  Statement.class.getClassLoader(),
                  new Class<?>[] {Statement.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("execute")) {
                      executedSql.add((String) arguments[0]);
                      return true;
                    }
                    return defaultValue(method.getReturnType());
                  });
      connection =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                      case "setAutoCommit" -> {
                        autoCommit.set((Boolean) arguments[0]);
                        yield null;
                      }
                      case "isWrapperFor" -> postgresql.get() && arguments[0] == PGConnection.class;
                      case "unwrap" -> {
                        if (postgresql.get() && arguments[0] == PGConnection.class) {
                          yield pgConnection;
                        }
                        throw new SQLException("not a PostgreSQL wrapper");
                      }
                      case "createStatement" -> statement;
                      case "close" -> {
                        closed.set(true);
                        closedLatch.countDown();
                        closePoll.countDown();
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
  }
}
