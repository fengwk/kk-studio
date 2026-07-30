package fun.fengwk.kkstudio.core.ai.runtime.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** RedisRealtimeEventSink 集成测试：append/read/trim/per-thread isolation/三种 delta。 */
class RedisRealtimeEventSinkIntegrationTest extends RedisSpringTestSupport {

  @Autowired private StringRedisTemplate stringRedisTemplate;
  @Autowired private HarnessRedisProperties properties;
  @Autowired private RealtimeEventJsonCodec eventCodec;

  private RedisRealtimeEventSink sink;
  private AtomicLong maxLength;

  @BeforeEach
  void setupSink() {
    properties.setRealtimeKeyPrefix("kk-studio:harness:realtime:");
    maxLength = new AtomicLong(1_000L);
    stringRedisTemplate.delete(properties.realtimeKey(1L));
    stringRedisTemplate.delete(properties.realtimeKey(2L));
    stringRedisTemplate.delete(properties.realtimeKey(7L));
    stringRedisTemplate.delete(properties.realtimeKey(42L));
    stringRedisTemplate.delete("kk-studio:harness:realtime:trim:7");
    stringRedisTemplate.delete("kk-studio:harness:realtime:thread:42");
    sink =
        new RedisRealtimeEventSink(
            () -> stringRedisTemplate, properties, eventCodec, maxLength::get);
  }

  @Test
  void textDeltaAppendAndRead() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(1L, 42L, 1, 1L, new ProviderStreamEvent.TextDelta("hi"), now);
    sink.append(event);

    String key = properties.realtimeKey(1L);
    List<ObjectRecord<String, String>> records =
        stringRedisTemplate.opsForStream().range(String.class, key, Range.unbounded());
    assertEquals(1, records.size());
    String body = records.get(0).getValue();
    assertEquals(eventCodec.encode(event), body);
    assertEquals(event, eventCodec.decode(body));
  }

  @Test
  void thinkingDeltaAppendAndRead() {
    Instant now = Instant.parse("2026-01-02T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            1L, 9L, 2, 1L, new ProviderStreamEvent.ThinkingDelta("plan"), now);
    sink.append(event);
    List<ObjectRecord<String, String>> records =
        stringRedisTemplate
            .opsForStream()
            .range(String.class, properties.realtimeKey(1L), Range.unbounded());
    assertEquals(1, records.size());
    assertEquals(event, eventCodec.decode(records.get(0).getValue()));
  }

  @Test
  void toolCallDeltaAppendPreservesRawFragment() {
    Instant now = Instant.parse("2026-01-03T00:00:00Z");
    ProviderStreamEvent.ToolCallDelta delta =
        new ProviderStreamEvent.ToolCallDelta(0, "call-1", "read", "{\"path\":\"REA");
    sink.append(new RealtimeEvent.ModelDelta(1L, 7L, 1, 1L, delta, now));

    String key = properties.realtimeKey(1L);
    List<ObjectRecord<String, String>> records =
        stringRedisTemplate.opsForStream().range(String.class, key, Range.unbounded());
    assertEquals(1, records.size());
    RealtimeEvent.ModelDelta decoded =
        (RealtimeEvent.ModelDelta) eventCodec.decode(records.get(0).getValue());
    assertEquals(delta, decoded.delta());
  }

  @Test
  void exactTrimKeepsLengthBounded() {
    maxLength.set(3);
    properties.setRealtimeKeyPrefix("kk-studio:harness:realtime:trim:");
    sink =
        new RedisRealtimeEventSink(
            () -> stringRedisTemplate, properties, eventCodec, maxLength::get);
    stringRedisTemplate.delete(properties.realtimeKey(7L));

    Instant now = Instant.parse("2026-01-04T00:00:00Z");
    for (int i = 0; i < 8; i++) {
      sink.append(
          new RealtimeEvent.ModelDelta(
              7L,
              100L + i,
              1,
              1L,
              new ProviderStreamEvent.TextDelta("frag-" + i),
              now.plusMillis(i)));
    }

    String key = properties.realtimeKey(7L);
    Long length = stringRedisTemplate.opsForStream().size(key);
    assertTrue(length != null && length <= 3, "stream length must be <= maxLength, got " + length);

    List<ObjectRecord<String, String>> records =
        stringRedisTemplate.opsForStream().range(String.class, key, Range.unbounded());
    assertEquals(length.intValue(), records.size());

    // The most recent record must be the latest fragment.
    RealtimeEvent.ModelDelta last =
        (RealtimeEvent.ModelDelta) eventCodec.decode(records.get(records.size() - 1).getValue());
    assertEquals("frag-7", ((ProviderStreamEvent.TextDelta) last.delta()).text());
  }

  @Test
  void changedPolicyAppliesToAnExistingStreamOnItsNextAppendWithoutRestoringTrimmedEvents() {
    maxLength.set(5);
    Instant now = Instant.parse("2026-01-04T01:00:00Z");
    for (int i = 0; i < 5; i++) {
      appendFragment(7L, i, now);
    }

    maxLength.set(3);
    appendFragment(7L, 5, now);
    String key = properties.realtimeKey(7L);
    assertEquals(3L, stringRedisTemplate.opsForStream().size(key));

    maxLength.set(6);
    appendFragment(7L, 6, now);
    assertEquals(
        4L,
        stringRedisTemplate.opsForStream().size(key),
        "increasing max length affects later writes but cannot restore trimmed records");
  }

  @Test
  void perThreadStreamsAreIsolated() {
    Instant now = Instant.parse("2026-01-05T00:00:00Z");
    sink.append(
        new RealtimeEvent.ModelDelta(
            1L, 100L, 1, 1L, new ProviderStreamEvent.TextDelta("one"), now));
    sink.append(
        new RealtimeEvent.ModelDelta(
            2L, 200L, 1, 1L, new ProviderStreamEvent.TextDelta("two"), now));

    String key1 = properties.realtimeKey(1L);
    String key2 = properties.realtimeKey(2L);
    assertEquals(1L, stringRedisTemplate.opsForStream().size(key1));
    assertEquals(1L, stringRedisTemplate.opsForStream().size(key2));

    List<ObjectRecord<String, String>> r1 =
        stringRedisTemplate.opsForStream().range(String.class, key1, Range.unbounded());
    List<ObjectRecord<String, String>> r2 =
        stringRedisTemplate.opsForStream().range(String.class, key2, Range.unbounded());
    assertEquals(
        "one",
        ((ProviderStreamEvent.TextDelta)
                ((RealtimeEvent.ModelDelta) eventCodec.decode(r1.get(0).getValue())).delta())
            .text());
    assertEquals(
        "two",
        ((ProviderStreamEvent.TextDelta)
                ((RealtimeEvent.ModelDelta) eventCodec.decode(r2.get(0).getValue())).delta())
            .text());
  }

  @Test
  void streamIsKeyedByPrefixAndDecimalThreadId() {
    properties.setRealtimeKeyPrefix("kk-studio:harness:realtime:thread:");
    sink =
        new RedisRealtimeEventSink(
            () -> stringRedisTemplate, properties, eventCodec, maxLength::get);

    Instant now = Instant.parse("2026-01-06T00:00:00Z");
    sink.append(
        new RealtimeEvent.ModelDelta(42L, 1L, 1, 1L, new ProviderStreamEvent.TextDelta("x"), now));

    String expected = "kk-studio:harness:realtime:thread:42";
    Long length = stringRedisTemplate.opsForStream().size(expected);
    assertEquals(1L, length);
  }

  private void appendFragment(long threadId, int index, Instant now) {
    sink.append(
        new RealtimeEvent.ModelDelta(
            threadId,
            100L + index,
            1,
            1L,
            new ProviderStreamEvent.TextDelta("frag-" + index),
            now.plusMillis(index)));
  }
}
