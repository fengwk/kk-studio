package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code environment_changed} 通知的 PostgreSQL 集成测试基座。
 *
 * <p>schema 当前仍带 environment 与 environment_connection 两个数据库行触发器。本基座在每个测试前只在隔离测试库中删除这两个
 * 触发器，使本类观测到的通知只可能来自 Java 生产写入口。
 *
 * <p>观察者使用独立 JDBC 连接 LISTEN 同一 channel，因此「提交后才可见」「回滚与未提交不可见」是被直接观测的事实。
 */
public abstract class EnvironmentNotificationTestSupport extends PostgresSpringTestSupport {

  protected static final String ENVIRONMENT_CHANNEL = "environment_changed";

  @Autowired protected DataSource dataSource;
  @Autowired protected PlatformTransactionManager transactionManager;
  @Autowired protected JdbcTemplate jdbcTemplate;

  @BeforeEach
  void dropLegacyEnvironmentNotifyTriggers() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        Statement statement = conn.createStatement()) {
      statement.execute("drop trigger if exists trg_environment_registry_changed on environment");
      statement.execute(
          "drop trigger if exists trg_environment_connection_changed on environment_connection");
    }
  }

  /** 打开一条独立观察者连接并 LISTEN {@code environment_changed}。 */
  protected EnvironmentChannelListener listen() throws SQLException {
    return new EnvironmentChannelListener(dataSource.getConnection());
  }

  /** 独立观察者连接：只等待确定数量的已提交通知，因此「没有通知」也是被观测的结果。 */
  protected static final class EnvironmentChannelListener implements AutoCloseable {

    private static final long AWAIT_TIMEOUT_MILLIS = 5_000;
    private static final int SILENCE_POLL_MILLIS = 200;

    private final Connection connection;
    private final PGConnection notifications;

    private EnvironmentChannelListener(Connection connection) throws SQLException {
      this.connection = connection;
      connection.setAutoCommit(true);
      this.notifications = connection.unwrap(PGConnection.class);
      try (Statement statement = connection.createStatement()) {
        statement.execute("listen " + ENVIRONMENT_CHANNEL);
      }
    }

    /** 断言恰好收到一条指定 payload 的已提交通知。 */
    void assertNotification(String expectedPayload) {
      List<String> payloads;
      long deadlineNanos = System.nanoTime() + AWAIT_TIMEOUT_MILLIS * 1_000_000L;
      do {
        payloads = collect(SILENCE_POLL_MILLIS);
      } while (payloads.isEmpty() && System.nanoTime() < deadlineNanos);
      assertEquals(List.of(expectedPayload), payloads);
    }

    /** 断言当前没有任何已提交通知：未提交、回滚与围栏静默都由本方法直接观测。 */
    void assertSilent() {
      assertEquals(List.of(), collect(SILENCE_POLL_MILLIS));
    }

    private List<String> collect(int pollMillis) {
      List<String> payloads = new ArrayList<>();
      try {
        PGNotification[] received = notifications.getNotifications(pollMillis);
        if (received == null) {
          return payloads;
        }
        for (PGNotification notification : received) {
          assertEquals(ENVIRONMENT_CHANNEL, notification.getName());
          payloads.add(notification.getParameter());
        }
        return payloads;
      } catch (SQLException error) {
        throw new IllegalStateException("environment notification collection failed", error);
      }
    }

    @Override
    public void close() throws SQLException {
      connection.close();
    }
  }
}
