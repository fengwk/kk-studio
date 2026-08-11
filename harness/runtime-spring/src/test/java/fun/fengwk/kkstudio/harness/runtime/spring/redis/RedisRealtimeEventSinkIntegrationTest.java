package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** RedisRealtimeEventSink 集成测试：append/exact trim/per-thread isolation/三种 delta 与 tool partial。 */
class RedisRealtimeEventSinkIntegrationTest {

  private static final RedisRealtimeConfig CONFIG = new RedisRealtimeConfig();
  private static final RealtimeEventJsonCodec CODEC = new RealtimeEventJsonCodec();

  private StringRedisTemplate template;
  private RedisRealtimeEventSink sink;

  @BeforeEach
  void setUp() {
    template = RedisRealtimeFixture.template();
    RedisRealtimeFixture.reset(CONFIG.prefix());
    sink = new RedisRealtimeEventSink(template, CONFIG, CODEC);
  }

  @Test
  void textDeltaAppendWritesSingleEventField() {
    Instant now = Instant.parse("2026-08-05T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            id(1L), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta("hi"), now);
    sink.append(event);

    String key = CONFIG.key(id(1L));
    List<MapRecord<String, Object, Object>> records =
        template.opsForStream().range(key, Range.unbounded());
    assertEquals(1, records.size());
    assertEquals(Map.of("event", CODEC.encode(event)), records.get(0).getValue());
    assertEquals(event, CODEC.decode(records.get(0).getValue().get("event").toString()));
  }

  @Test
  void thinkingDeltaAppendAndRead() {
    Instant now = Instant.parse("2026-08-05T00:01:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            id(1L), id(9L), 2, 1L, new ProviderStreamEvent.ThinkingDelta("plan"), now);
    sink.append(event);
    List<MapRecord<String, Object, Object>> records =
        template.opsForStream().range(CONFIG.key(id(1L)), Range.unbounded());
    assertEquals(1, records.size());
    assertEquals(event, CODEC.decode(records.get(0).getValue().get("event").toString()));
  }

  @Test
  void toolCallDeltaAppendPreservesRawFragment() {
    Instant now = Instant.parse("2026-08-05T00:02:00Z");
    ProviderStreamEvent.ToolCallDelta delta =
        new ProviderStreamEvent.ToolCallDelta(0, "call-1", "read", "{\"path\":\"REA");
    sink.append(new RealtimeEvent.ModelDelta(id(1L), id(7L), 1, 1L, delta, now));
    List<MapRecord<String, Object, Object>> records =
        template.opsForStream().range(CONFIG.key(id(1L)), Range.unbounded());
    RealtimeEvent.ModelDelta decoded =
        (RealtimeEvent.ModelDelta) CODEC.decode(records.get(0).getValue().get("event").toString());
    assertEquals(delta, decoded.delta());
  }

  @Test
  void toolPartialAppendAndRead() {
    Instant now = Instant.parse("2026-08-05T00:03:00Z");
    RealtimeEvent.ToolPartial event =
        new RealtimeEvent.ToolPartial(
            id(1L),
            id(99L),
            2,
            new ToolResult("call-1", List.of(new TextToolContent("partial")), false, "{}", false),
            now);
    sink.append(event);
    List<MapRecord<String, Object, Object>> records =
        template.opsForStream().range(CONFIG.key(id(1L)), Range.unbounded());
    assertEquals(1, records.size());
    assertEquals(event, CODEC.decode(records.get(0).getValue().get("event").toString()));
  }

  @Test
  void perThreadStreamsAreIsolated() {
    Instant now = Instant.parse("2026-08-05T00:04:00Z");
    sink.append(
        new RealtimeEvent.ModelDelta(
            id(1L), id(100L), 1, 1L, new ProviderStreamEvent.TextDelta("one"), now));
    sink.append(
        new RealtimeEvent.ModelDelta(
            id(2L), id(200L), 1, 1L, new ProviderStreamEvent.TextDelta("two"), now));

    assertEquals(1L, template.opsForStream().size(CONFIG.key(id(1L))));
    assertEquals(1L, template.opsForStream().size(CONFIG.key(id(2L))));
    List<MapRecord<String, Object, Object>> r1 =
        template.opsForStream().range(CONFIG.key(id(1L)), Range.unbounded());
    List<MapRecord<String, Object, Object>> r2 =
        template.opsForStream().range(CONFIG.key(id(2L)), Range.unbounded());
    RealtimeEvent.ModelDelta decoded1 =
        (RealtimeEvent.ModelDelta) CODEC.decode(r1.get(0).getValue().get("event").toString());
    RealtimeEvent.ModelDelta decoded2 =
        (RealtimeEvent.ModelDelta) CODEC.decode(r2.get(0).getValue().get("event").toString());
    assertEquals("one", ((ProviderStreamEvent.TextDelta) decoded1.delta()).text());
    assertEquals("two", ((ProviderStreamEvent.TextDelta) decoded2.delta()).text());
  }

  @Test
  void exactTrimKeepsLengthBounded() {
    RedisRealtimeConfig trimmedConfig = new RedisRealtimeConfig(CONFIG.prefix(), 3);
    RedisRealtimeEventSink trimmedSink = new RedisRealtimeEventSink(template, trimmedConfig, CODEC);
    Instant now = Instant.parse("2026-08-05T00:05:00Z");
    for (int i = 0; i < 8; i++) {
      trimmedSink.append(
          new RealtimeEvent.ModelDelta(
              id(3L),
              id(100L + i),
              1,
              1L,
              new ProviderStreamEvent.TextDelta("frag-" + i),
              now.plusMillis(i)));
    }

    String key = CONFIG.key(id(3L));
    Long length = template.opsForStream().size(key);
    assertTrue(
        length != null && length == 3L, "exact trim must keep maxLength records, got " + length);
    List<MapRecord<String, Object, Object>> records =
        template.opsForStream().range(key, Range.unbounded());
    assertEquals(3, records.size());
    for (int index = 0; index < records.size(); index++) {
      RealtimeEvent.ModelDelta retained =
          (RealtimeEvent.ModelDelta)
              CODEC.decode(records.get(index).getValue().get("event").toString());
      assertEquals(
          "frag-" + (index + 5), ((ProviderStreamEvent.TextDelta) retained.delta()).text());
    }
  }

  @Test
  void streamIsKeyedByPrefixAndUuidThreadId() {
    String prefix = "kk-studio:harness:realtime:key:";
    RedisRealtimeConfig keyedConfig = new RedisRealtimeConfig(prefix, 5000);
    RedisRealtimeEventSink keyedSink = new RedisRealtimeEventSink(template, keyedConfig, CODEC);
    Instant now = Instant.parse("2026-08-05T00:06:00Z");
    keyedSink.append(
        new RealtimeEvent.ModelDelta(
            id(42L), id(1L), 1, 1L, new ProviderStreamEvent.TextDelta("x"), now));

    assertEquals(1L, template.opsForStream().size(prefix + id(42L)));
  }
}
