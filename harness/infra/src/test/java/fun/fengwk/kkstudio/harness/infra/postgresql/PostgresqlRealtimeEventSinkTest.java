package fun.fengwk.kkstudio.harness.infra.postgresql;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** PostgresqlRealtimeEventSink 的单条与批量 pg_notify、payload 上限、顺序保持、分块与异常传播契约。 */
class PostgresqlRealtimeEventSinkTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
  private static final String BATCH_SQL =
      "select pg_notify(?, payload)"
          + " from unnest(?::text[]) with ordinality as t(payload, ord)"
          + " order by ord";

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

  /** 空列表不发起任何 SQL 往返：批量发布必须对无事件保持零成本。 */
  @Test
  void appendAllWithEmptyListIssuesNoSql() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);

    sink.appendAll(List.of());

    assertEquals(0, jdbcTemplate.executions);
    assertNull(jdbcTemplate.sql);
  }

  /**
   * 批量发布必须把 N 个事件各自的 canonical envelope 放进**同一次** SQL 往返，使用 unnest + ordinality 保证顺序， 并保持每个
   * envelope 与单条发布完全一致（不是合并成一个大 envelope）。
   */
  @Test
  void appendAllSendsEveryEnvelopeInOneOrderedRoundtrip() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    List<RealtimeEvent> events = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      events.add(modelDelta("d" + i));
    }

    sink.appendAll(events);

    assertEquals(1, jdbcTemplate.executions, "whole batch must use exactly one SQL roundtrip");
    assertEquals(BATCH_SQL, jdbcTemplate.sql);
    assertEquals("harness_realtime", jdbcTemplate.channel);
    assertEquals(1, jdbcTemplate.batchSizes.size());
    assertEquals(5, jdbcTemplate.batchSizes.getFirst());
    assertPayloadsMatch(jdbcTemplate.payloads, events);
    assertTrue(jdbcTemplate.arrayFreed.get(), "JDBC Array must be released after the roundtrip");
  }

  /** 批量内单个超大事件只把**该事件**降级为 RESYNC；同一批其余事件仍保持完整 EVENT，且总往返次数不变。 */
  @Test
  void appendAllDegradesOnlyTheOversizedEventWithinTheBatch() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    RealtimeEvent.ModelDelta oversized = modelDelta("界".repeat(3000));
    RealtimeEvent.ModelDelta normal = modelDelta("small");
    assertTrue(
        codec.encodeEvent(oversized).getBytes(StandardCharsets.UTF_8).length
            > PostgresqlRealtimeEventSink.MAX_NOTIFICATION_BYTES);

    sink.appendAll(List.of(normal, oversized));

    assertEquals(1, jdbcTemplate.executions);
    assertEquals(2, jdbcTemplate.payloads.size());
    RealtimeNotificationCodec.Envelope.Event first =
        assertInstanceOf(
            RealtimeNotificationCodec.Envelope.Event.class,
            codec.decode(jdbcTemplate.payloads.get(0)));
    assertEquals(normal, first.event());
    RealtimeNotificationCodec.Envelope.Resync second =
        assertInstanceOf(
            RealtimeNotificationCodec.Envelope.Resync.class,
            codec.decode(jdbcTemplate.payloads.get(1)));
    assertEquals(oversized.threadId(), second.threadId());
    assertEquals(PostgresqlRealtimeEventSink.OVERSIZE_REASON, second.reason());
    assertTrue(jdbcTemplate.arrayFreed.get());
  }

  /** 超过事件数上界的批次必须拆成多个有界往返，每个往返内部与整体都保持输入顺序。 */
  @Test
  void appendAllSplitsBatchesByEventCountBound() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    int total = PostgresqlRealtimeEventSink.MAX_BATCH_EVENTS + 1;
    List<RealtimeEvent> events = new ArrayList<>();
    for (int i = 1; i <= total; i++) {
      events.add(modelDelta("d" + i));
    }

    sink.appendAll(events);

    assertEquals(2, jdbcTemplate.executions, "overflow must split into bounded roundtrips");
    assertEquals(
        List.of(PostgresqlRealtimeEventSink.MAX_BATCH_EVENTS, 1),
        jdbcTemplate.batchSizes,
        "each chunk must be capped at MAX_BATCH_EVENTS");
    assertPayloadsMatch(jdbcTemplate.payloads, events);
  }

  /**
   * 超过编码后 payload 字节上界的批次同样必须拆分为多个有界往返：每个 chunk 的编码字节总量不超过 {@code MAX_BATCH_BYTES}，前提是 chunk 尚未达到
   * {@code MAX_BATCH_EVENTS}（即分块确实由字节上界触发）。
   */
  @Test
  void appendAllSplitsBatchesByEncodedByteBound() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    // 每个事件编码后在 7KB 左右（仍低于单条 7900 bytes 上限，保持 EVENT 而不降级 RESYNC）。
    String filler = "x".repeat(7000);
    int total = 40;
    List<RealtimeEvent> events = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      events.add(modelDelta(filler + i));
    }
    int singleEventBytes =
        codec.encodeEvent(events.getFirst()).getBytes(StandardCharsets.UTF_8).length;
    assertTrue(
        singleEventBytes <= PostgresqlRealtimeEventSink.MAX_NOTIFICATION_BYTES,
        "fixture events must stay below the single-notification limit");
    assertTrue(
        (long) singleEventBytes * total > PostgresqlRealtimeEventSink.MAX_BATCH_BYTES,
        "fixture must exceed the batch byte bound in total");
    assertTrue(
        total < PostgresqlRealtimeEventSink.MAX_BATCH_EVENTS,
        "fixture must stay below the event-count bound so the split is byte-driven");

    sink.appendAll(events);

    assertEquals(2, jdbcTemplate.executions, "byte overflow must split into bounded roundtrips");
    assertEquals(total, jdbcTemplate.payloads.size());
    assertTrue(
        jdbcTemplate.batchSizes.getFirst() < total,
        "byte bound must cap the first chunk below the event total");
    for (int chunkSize : jdbcTemplate.batchSizes) {
      assertTrue(chunkSize < PostgresqlRealtimeEventSink.MAX_BATCH_EVENTS);
    }
    assertPayloadsMatch(jdbcTemplate.payloads, events);
    assertTrue(jdbcTemplate.arrayFreed.get());
  }

  /** 批量往返失败必须原样传播且仍然释放 JDBC Array，不吞掉数据库错误。 */
  @Test
  void appendAllPropagatesFailureAndReleasesArray() {
    SQLException failure = new SQLException("batch notify failed");
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    jdbcTemplate.failOnExecute = failure;
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);

    assertThrows(
        UncategorizedSQLException.class,
        () -> sink.appendAll(List.of(modelDelta("a"), modelDelta("b"))));

    assertTrue(jdbcTemplate.arrayFreed.get(), "Array must be freed even when the roundtrip fails");
  }

  private static void assertPayloadsMatch(List<String> payloads, List<RealtimeEvent> events) {
    assertEquals(events.size(), payloads.size());
    RealtimeNotificationCodec codec = new RealtimeNotificationCodec();
    for (int i = 0; i < events.size(); i++) {
      RealtimeNotificationCodec.Envelope.Event notification =
          assertInstanceOf(
              RealtimeNotificationCodec.Envelope.Event.class, codec.decode(payloads.get(i)));
      assertEquals(events.get(i), notification.event(), "order must match input order at " + i);
    }
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  /**
   * 记录型 JdbcTemplate：同时支持单条 {@code setString(2, payload)} 与批量 {@code setArray(2, text[])} 两种调用形态，
   * 记录 SQL、channel、每个 chunk 的事件数与展开后的 payload 顺序，并跟踪 JDBC Array 的释放。
   */
  private static class RecordingJdbcTemplate extends JdbcTemplate {

    private String sql;
    private String channel;
    private String payload;
    private final List<String> payloads = new ArrayList<>();
    private final List<Integer> batchSizes = new ArrayList<>();
    private final AtomicBoolean arrayFreed = new AtomicBoolean();
    private int executions;
    private boolean executed;
    SQLException failOnExecute;

    @Override
    public <T> T execute(String sql, PreparedStatementCallback<T> action) {
      this.sql = sql;
      this.executions++;
      PreparedStatement statement =
          (PreparedStatement)
              Proxy.newProxyInstance(
                  PreparedStatement.class.getClassLoader(),
                  new Class<?>[] {PreparedStatement.class},
                  (proxy, method, args) -> {
                    switch (method.getName()) {
                      case "setString" -> {
                        int index = (int) args[0];
                        if (index == 1) {
                          channel = (String) args[1];
                        } else if (index == 2) {
                          payload = (String) args[1];
                        }
                        return null;
                      }
                      case "setArray" -> {
                        String[] values = ((TextArray) args[1]).values();
                        batchSizes.add(values.length);
                        payloads.addAll(List.of(values));
                        return null;
                      }
                      case "getConnection" -> {
                        return connectionWithArrayFactory();
                      }
                      case "execute" -> {
                        if (failOnExecute != null) {
                          throw failOnExecute;
                        }
                        executed = true;
                        return true;
                      }
                      default -> {
                        return defaultValue(method.getReturnType());
                      }
                    }
                  });
      try {
        return action.doInPreparedStatement(statement);
      } catch (SQLException error) {
        throw new UncategorizedSQLException("cannot execute realtime notification", sql, error);
      }
    }

    /** 只暴露 createArrayOf；返回的 Array 记录 free 调用。 */
    private Object connectionWithArrayFactory() {
      return Proxy.newProxyInstance(
          Connection.class.getClassLoader(),
          new Class<?>[] {Connection.class},
          (proxy, method, args) -> {
            if (method.getName().equals("createArrayOf")) {
              return new TextArray((String[]) args[1], arrayFreed);
            }
            throw new UnsupportedOperationException(method.getName());
          });
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

  /** 极简 JDBC Array：只支持取值与 free。 */
  private static final class TextArray implements Array {

    private final String[] values;
    private final AtomicBoolean freed;

    TextArray(String[] values, AtomicBoolean freed) {
      this.values = values;
      this.freed = freed;
    }

    String[] values() {
      return values;
    }

    @Override
    public String getBaseTypeName() {
      return "text";
    }

    @Override
    public int getBaseType() {
      return Types.VARCHAR;
    }

    @Override
    public Object getArray() {
      return values;
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) {
      return values;
    }

    @Override
    public Object getArray(long index, int count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(long index, int count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void free() {
      freed.set(true);
    }
  }
}
