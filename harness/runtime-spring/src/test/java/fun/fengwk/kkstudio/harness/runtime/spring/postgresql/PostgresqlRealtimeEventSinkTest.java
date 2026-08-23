package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/** PostgresqlRealtimeEventSink 的 pg_notify、payload 上限与异常传播契约。 */
class PostgresqlRealtimeEventSinkTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();

  /** 小型事件必须通过注入的 JdbcTemplate 调用参数化 pg_notify，并保持完整 EVENT envelope。 */
  @Test
  void appendSendsCanonicalEventThroughJdbcTemplate() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    RealtimeEvent.ModelDelta event = modelDelta("hello");

    sink.append(event);

    assertEquals("select pg_notify(?, ?)", jdbcTemplate.sql);
    assertEquals("harness_realtime", jdbcTemplate.channel);
    RealtimeNotificationCodec.Envelope.Event notification =
        assertInstanceOf(
            RealtimeNotificationCodec.Envelope.Event.class, codec.decode(jdbcTemplate.payload));
    assertEquals(event, notification.event());
    assertTrue(jdbcTemplate.executed);
  }

  /** 以最终 canonical notification 的 UTF-8 bytes 判断上限；超限时必须发送可安全承载的小型 RESYNC。 */
  @Test
  void oversizedEventIsReplacedWithSmallResyncNotification() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    RealtimeEvent.ModelDelta event = modelDelta("界".repeat(3000));
    assertTrue(
        codec.encodeEvent(event).getBytes(StandardCharsets.UTF_8).length
            > PostgresqlRealtimeEventSink.MAX_NOTIFICATION_BYTES);

    sink.append(event);

    RealtimeNotificationCodec.Envelope.Resync notification =
        assertInstanceOf(
            RealtimeNotificationCodec.Envelope.Resync.class, codec.decode(jdbcTemplate.payload));
    assertEquals(event.threadId(), notification.threadId());
    assertEquals(PostgresqlRealtimeEventSink.OVERSIZE_REASON, notification.reason());
    assertTrue(
        jdbcTemplate.payload.getBytes(StandardCharsets.UTF_8).length
            <= PostgresqlRealtimeEventSink.MAX_NOTIFICATION_BYTES);
  }

  /** 恰好 7900 UTF-8 bytes 仍属于 EVENT 安全边界，不能提前退化为 RESYNC。 */
  @Test
  void eventAtNotificationByteLimitIsStillSentAsEvent() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    int emptyEventBytes = codec.encodeEvent(modelDelta("")).getBytes(StandardCharsets.UTF_8).length;
    RealtimeEvent.ModelDelta event =
        modelDelta(
            "a".repeat(PostgresqlRealtimeEventSink.MAX_NOTIFICATION_BYTES - emptyEventBytes));
    assertEquals(
        PostgresqlRealtimeEventSink.MAX_NOTIFICATION_BYTES,
        codec.encodeEvent(event).getBytes(StandardCharsets.UTF_8).length);

    sink.append(event);

    RealtimeNotificationCodec.Envelope.Event notification =
        assertInstanceOf(
            RealtimeNotificationCodec.Envelope.Event.class, codec.decode(jdbcTemplate.payload));
    assertEquals(event, notification.event());
  }

  /** JdbcTemplate/数据库异常不在 sink 内吞掉，交由 Runtime 既有 append 边界隔离。 */
  @Test
  void jdbcTemplateFailurePropagates() {
    IllegalStateException failure = new IllegalStateException("database unavailable");
    JdbcTemplate jdbcTemplate =
        new RecordingJdbcTemplate() {
          @Override
          public <T> T execute(String sql, PreparedStatementCallback<T> action) {
            throw failure;
          }
        };
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);

    assertEquals(
        failure, assertThrows(IllegalStateException.class, () -> sink.append(modelDelta("x"))));
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private static class RecordingJdbcTemplate extends JdbcTemplate {

    private String sql;
    private String channel;
    private String payload;
    private boolean executed;

    @Override
    public <T> T execute(String sql, PreparedStatementCallback<T> action) {
      this.sql = sql;
      PreparedStatement statement =
          (PreparedStatement)
              Proxy.newProxyInstance(
                  PreparedStatement.class.getClassLoader(),
                  new Class<?>[] {PreparedStatement.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("setString")) {
                      int index = (int) args[0];
                      if (index == 1) {
                        channel = (String) args[1];
                      } else if (index == 2) {
                        payload = (String) args[1];
                      }
                      return null;
                    }
                    if (method.getName().equals("execute")) {
                      executed = true;
                      return true;
                    }
                    return defaultValue(method.getReturnType());
                  });
      try {
        return action.doInPreparedStatement(statement);
      } catch (SQLException error) {
        throw new AssertionError(error);
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
  }
}
