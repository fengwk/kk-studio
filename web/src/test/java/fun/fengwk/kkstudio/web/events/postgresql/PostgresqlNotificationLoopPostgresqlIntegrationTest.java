package fun.fengwk.kkstudio.web.events.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PostgreSQL notification loop 的真实连接回归。
 *
 * <p>使用单连接 Hikari 池，让 listener 持有的 proxy connection 与真实 PostgreSQL poll 共同覆盖连接中止、连接归还和断连重连路径。
 */
class PostgresqlNotificationLoopPostgresqlIntegrationTest {

  private static final String CHANNEL = "notification_loop_integration";
  private static final String APPLICATION_NAME = "notification-loop-integration";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_notification_loop_test");

  static {
    POSTGRES.start();
  }

  @Test
  void closeAbortsRealHikariPollWithinOneSecondAfterResync() throws Exception {
    CountDownLatch resynced = new CountDownLatch(1);
    CountDownLatch notificationReceived = new CountDownLatch(1);
    AtomicReference<String> payload = new AtomicReference<>();

    try (HikariDataSource dataSource = newDataSource()) {
      PostgresqlNotificationLoop loop =
          new PostgresqlNotificationLoop(
              dataSource,
              List.of(
                  new PostgresqlNotificationHandler(
                      CHANNEL,
                      value -> {
                        payload.set(value);
                        notificationReceived.countDown();
                      },
                      resynced::countDown)),
              Duration.ofSeconds(5),
              Duration.ofMillis(10));
      try {
        loop.start();
        assertTrue(resynced.await(5, TimeUnit.SECONDS), "listener must resync before polling");
        notify("before-close");
        assertTrue(
            notificationReceived.await(5, TimeUnit.SECONDS),
            "listener must reach the real PostgreSQL poll");
        assertEquals("before-close", payload.get());

        // resync 和真实 NOTIFY 都完成后才关闭，避免只覆盖连接建立前的竞态。
        long closeStarted = System.nanoTime();
        loop.close();
        long closeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);

        assertTrue(
            closeElapsedMillis < 1_000,
            "abort must release the five-second PostgreSQL poll without waiting for timeout");
        assertFalse(loop.isRunning());
      } finally {
        loop.close();
      }
    }
  }

  @Test
  void reconnectsAfterBackendTerminationAndReceivesNotificationAfterResync() throws Exception {
    AtomicInteger resyncs = new AtomicInteger();
    CountDownLatch firstResync = new CountDownLatch(1);
    CountDownLatch secondResync = new CountDownLatch(1);
    CountDownLatch notificationReceived = new CountDownLatch(1);
    AtomicReference<String> payload = new AtomicReference<>();

    try (HikariDataSource dataSource = newDataSource()) {
      PostgresqlNotificationLoop loop =
          new PostgresqlNotificationLoop(
              dataSource,
              List.of(
                  new PostgresqlNotificationHandler(
                      CHANNEL,
                      value -> {
                        payload.set(value);
                        notificationReceived.countDown();
                      },
                      () -> {
                        int resyncCount = resyncs.incrementAndGet();
                        if (resyncCount == 1) {
                          firstResync.countDown();
                        } else if (resyncCount == 2) {
                          secondResync.countDown();
                        }
                      })),
              Duration.ofSeconds(5),
              Duration.ofMillis(10));
      try {
        loop.start();
        assertTrue(firstResync.await(5, TimeUnit.SECONDS), "initial listener resync must finish");

        long backendPid = listenerBackendPid();
        terminateBackend(backendPid);
        assertTrue(
            secondResync.await(10, TimeUnit.SECONDS),
            "terminating the listener backend must trigger reconnect and resync");

        notify("after-reconnect");
        assertTrue(
            notificationReceived.await(5, TimeUnit.SECONDS),
            "reconnected listener must receive subsequent NOTIFY");
        assertEquals("after-reconnect", payload.get());
        assertTrue(resyncs.get() >= 2);
      } finally {
        loop.close();
      }
    }
  }

  @Test
  void idlePollExceedingDriverSocketTimeoutDoesNotTriggerResyncAndReceivesNotification()
      throws Exception {
    AtomicInteger resyncs = new AtomicInteger();
    CountDownLatch firstResync = new CountDownLatch(1);
    CountDownLatch notificationReceived = new CountDownLatch(1);
    AtomicReference<String> payload = new AtomicReference<>();

    try (HikariDataSource dataSource = newDataSource()) {
      PostgresqlNotificationLoop loop =
          new PostgresqlNotificationLoop(
              dataSource,
              List.of(
                  new PostgresqlNotificationHandler(
                      CHANNEL,
                      value -> {
                        payload.set(value);
                        notificationReceived.countDown();
                      },
                      () -> {
                        resyncs.incrementAndGet();
                        firstResync.countDown();
                      })),
              Duration.ofSeconds(5),
              Duration.ofMillis(10));
      try {
        loop.start();
        assertTrue(firstResync.await(5, TimeUnit.SECONDS), "initial listener resync must finish");
        assertEquals(1, resyncs.get(), "initial resync must happen exactly once on startup");

        // 底层 PostgreSQL driver 设置了 socketTimeout=1 秒，而当前通知轮询周期为 5 秒。
        // PostgreSQL JDBC 的 QueryExecutorImpl.processNotifies 会在拉取通知时暂存原有 socket SO_TIMEOUT，
        // 并以 poll timeout（5 秒）临时覆盖，轮询结束或返回通知后再恢复原 SO_TIMEOUT。
        // 此处让当前线程空闲等待 1.5 秒（> 1 秒 driver socketTimeout，但在 5 秒轮询上限内），
        // 验证空闲期并未因底层 driver 1 秒 socketTimeout 触发超时断连和非预期的额外 resync。
        Thread.sleep(1500);
        assertEquals(
            1,
            resyncs.get(),
            "idle poll exceeding driver socketTimeout must not trigger reconnect or extra resync");
        assertTrue(loop.isRunning(), "loop must stay running across the idle poll");

        // 空闲结束后发送通知，验证该连接依然健康可用并能正常接收并投递通知
        notify("after-idle");
        assertTrue(
            notificationReceived.await(5, TimeUnit.SECONDS),
            "listener must receive subsequent NOTIFY after idle poll");
        assertEquals("after-idle", payload.get(), "payload must match after idle poll");
        assertEquals(
            1, resyncs.get(), "no extra resync must occur after successful notification delivery");
      } finally {
        loop.close();
      }
    }
  }

  private static HikariDataSource newDataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5_000);
    config.setPoolName("notification-loop-integration");
    config.addDataSourceProperty("ApplicationName", APPLICATION_NAME);
    // 配置更短的 driver connectTimeout 与 socketTimeout（1 秒），
    // 验证 notification loop 在底层 socketTimeout 短于通知轮询周期（5 秒）时不受干扰。
    config.addDataSourceProperty("connectTimeout", "1");
    config.addDataSourceProperty("socketTimeout", "1");
    return new HikariDataSource(config);
  }

  private static void notify(String payload) throws SQLException {
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("select pg_notify('" + CHANNEL + "', '" + payload + "')");
    }
  }

  private static long listenerBackendPid() throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select pid from pg_stat_activity"
                    + " where application_name = ? and pid <> pg_backend_pid()")) {
      statement.setString(1, APPLICATION_NAME);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new AssertionError("listener backend is not visible in pg_stat_activity");
        }
        return resultSet.getLong(1);
      }
    }
  }

  private static void terminateBackend(long backendPid) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement("select pg_terminate_backend(?)")) {
      statement.setInt(1, Math.toIntExact(backendPid));
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next() || !resultSet.getBoolean(1)) {
          throw new AssertionError("listener backend was not terminated: " + backendPid);
        }
      }
    }
  }

  private static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
