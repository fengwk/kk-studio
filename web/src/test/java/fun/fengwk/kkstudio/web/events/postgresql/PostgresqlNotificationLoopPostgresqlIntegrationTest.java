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
