package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通过 transaction-aware {@link JdbcTemplate} 写入 PostgreSQL NOTIFY 的 realtime sink。
 *
 * <p>单条 EVENT canonical notification 超过 PostgreSQL payload 安全上限时改发小型 RESYNC；批量发布保持同一降级规则，只是把每个事件各自的
 * envelope 放进一次有界 SQL 往返（{@code unnest ... order by ordinality}），顺序与单条发布逐条调用完全一致。数据库异常直接向上传播，由
 * Runtime 既有边界隔离。
 */
public final class PostgresqlRealtimeEventSink implements RealtimeEventSink {

  static final int MAX_NOTIFICATION_BYTES = 7900;
  static final String OVERSIZE_REASON = "EVENT_TOO_LARGE";

  /** 单次批量 NOTIFY 往返的事件数上界，避免超大批次构造无界 SQL 参数。 */
  static final int MAX_BATCH_EVENTS = 256;

  /** 单次批量 NOTIFY 往返的编码后 payload 字节上界。 */
  static final int MAX_BATCH_BYTES = 256 * 1024;

  private static final String NOTIFY_SQL = "select pg_notify(?, ?)";
  private static final String NOTIFY_BATCH_SQL =
      "select pg_notify(?, payload)"
          + " from unnest(?::text[]) with ordinality as t(payload, ord)"
          + " order by ord";

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
    String notification = encode(event);
    PreparedStatementCallback<Void> notify =
        statement -> {
          statement.setString(1, PostgresqlRealtimeChannel.NAME);
          statement.setString(2, notification);
          statement.execute();
          return null;
        };
    jdbcTemplate.execute(NOTIFY_SQL, notify);
  }

  /** 按入参顺序批量发布：每个有界分块恰好一次 SQL 往返，块内每个事件仍使用各自独立的 envelope，整体顺序与逐条 {@link #append} 一致。 */
  @Override
  public void appendAll(List<RealtimeEvent> events) {
    Objects.requireNonNull(events, "events");
    if (events.isEmpty()) {
      return;
    }
    List<String> chunk = new ArrayList<>();
    int chunkBytes = 0;
    for (RealtimeEvent event : events) {
      Objects.requireNonNull(event, "event");
      String notification = encode(event);
      int notificationBytes = notification.getBytes(StandardCharsets.UTF_8).length;
      if (!chunk.isEmpty()
          && (chunk.size() >= MAX_BATCH_EVENTS
              || chunkBytes + notificationBytes > MAX_BATCH_BYTES)) {
        notifyBatch(chunk);
        chunk = new ArrayList<>();
        chunkBytes = 0;
      }
      chunk.add(notification);
      chunkBytes += notificationBytes;
    }
    notifyBatch(chunk);
  }

  /** 单次批量 NOTIFY：channel 参数化，payload 数组按输入顺序展开；JDBC Array 无论成功或异常都必须释放。 */
  private void notifyBatch(List<String> notifications) {
    if (notifications.isEmpty()) {
      return;
    }
    PreparedStatementCallback<Void> notify =
        statement -> {
          Array payloads = null;
          try {
            payloads =
                statement
                    .getConnection()
                    .createArrayOf("text", notifications.toArray(String[]::new));
            statement.setString(1, PostgresqlRealtimeChannel.NAME);
            statement.setArray(2, payloads);
            statement.execute();
          } finally {
            if (payloads != null) {
              payloads.free();
            }
          }
          return null;
        };
    jdbcTemplate.execute(NOTIFY_BATCH_SQL, notify);
  }

  /** 编码为 canonical envelope；超出 PostgreSQL payload 安全上限时退化为该事件所属 Thread 的小型 RESYNC。 */
  private String encode(RealtimeEvent event) {
    String payload = notificationCodec.encodeEvent(event);
    if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_NOTIFICATION_BYTES) {
      return notificationCodec.encodeResync(event.threadId(), OVERSIZE_REASON);
    }
    return payload;
  }
}
