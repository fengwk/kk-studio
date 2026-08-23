package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 通过 transaction-aware {@link JdbcTemplate} 写入 PostgreSQL NOTIFY 的 realtime sink。
 *
 * <p>EVENT canonical notification 超过 PostgreSQL payload 安全上限时改发小型 RESYNC。数据库异常直接向上传播，由 Runtime
 * 既有边界隔离。
 */
public final class PostgresqlRealtimeEventSink implements RealtimeEventSink {

  static final int MAX_NOTIFICATION_BYTES = 7900;
  static final String OVERSIZE_REASON = "EVENT_TOO_LARGE";

  private static final String NOTIFY_SQL = "select pg_notify(?, ?)";

  private final JdbcTemplate jdbcTemplate;
  private final RealtimeNotificationCodec notificationCodec;

  public PostgresqlRealtimeEventSink(
      JdbcTemplate jdbcTemplate, RealtimeNotificationCodec notificationCodec) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.notificationCodec = Objects.requireNonNull(notificationCodec, "notificationCodec");
  }

  @Override
  public void append(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    String payload = notificationCodec.encodeEvent(event);
    if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_NOTIFICATION_BYTES) {
      payload = notificationCodec.encodeResync(event.threadId(), OVERSIZE_REASON);
    }
    String notification = payload;
    PreparedStatementCallback<Void> notify =
        statement -> {
          statement.setString(1, PostgresqlRealtimeChannel.NAME);
          statement.setString(2, notification);
          statement.execute();
          return null;
        };
    jdbcTemplate.execute(NOTIFY_SQL, notify);
  }
}
