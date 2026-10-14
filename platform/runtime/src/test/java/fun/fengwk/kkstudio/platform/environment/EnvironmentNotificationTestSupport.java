package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code environment_changed} 通知的 PostgreSQL 集成测试基座。
 *
 * <p>通知由 Java 生产写入口在成功写事实的同一事务内发布。观察者使用一条独立的非池化 JDBC 连接 LISTEN 同一 channel，因此「提交后才可见」
 * 「回滚与未提交不可见」是被直接观测的事实；连接由每个测试关闭，不依赖连接池归还，避免残留 LISTEN 或未消费通知掩盖错误。
 */
public abstract class EnvironmentNotificationTestSupport extends PostgresSpringTestSupport {

  protected static final String ENVIRONMENT_CHANNEL = "environment_changed";

  @Autowired protected PlatformTransactionManager transactionManager;
  @Autowired protected JdbcTemplate jdbcTemplate;

  /** 打开一条独立、非池化的观察者连接并 LISTEN {@code environment_changed}；由调用方 try-with-resources 关闭。 */
  protected EnvironmentChannelListener listen() throws SQLException {
    return new EnvironmentChannelListener(PostgresSchemaSupport.newConnection());
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
