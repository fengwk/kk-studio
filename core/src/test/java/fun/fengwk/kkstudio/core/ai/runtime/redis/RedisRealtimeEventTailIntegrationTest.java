package fun.fengwk.kkstudio.core.ai.runtime.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.core.ai.runtime.realtime.HarnessRealtimeEventTail;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** RedisRealtimeEventTail reads only records after the exclusive stream cursor. */
class RedisRealtimeEventTailIntegrationTest extends RedisSpringTestSupport {

  @Autowired private StringRedisTemplate stringRedisTemplate;
  @Autowired private HarnessRedisProperties properties;
  @Autowired private RealtimeEventJsonCodec eventCodec;
  @Autowired private HarnessRealtimeEventTail tail;
  private RedisRealtimeEventSink sink;

  @BeforeEach
  void clean() {
    stringRedisTemplate.delete(properties.realtimeKey(11L));
    sink =
        new RedisRealtimeEventSink(() -> stringRedisTemplate, properties, eventCodec, () -> 5_000L);
  }

  @Test
  void readsOnlyRecordsAfterCursor() {
    Instant now = Instant.parse("2026-07-24T00:00:00Z");
    sink.append(
        new RealtimeEvent.ModelDelta(
            11L, 1L, 1, 1L, new ProviderStreamEvent.TextDelta("one"), now));
    sink.append(
        new RealtimeEvent.ModelDelta(
            11L, 1L, 1, 2L, new ProviderStreamEvent.TextDelta("two"), now));

    List<HarnessRealtimeEventTail.Record> firstPage = tail.readAfter(11L, "0-0", 10, Duration.ZERO);
    assertEquals(2, firstPage.size());
    assertEquals(
        eventCodec.encode(
            new RealtimeEvent.ModelDelta(
                11L, 1L, 1, 1L, new ProviderStreamEvent.TextDelta("one"), now)),
        firstPage.get(0).payloadJson());

    List<HarnessRealtimeEventTail.Record> secondPage =
        tail.readAfter(11L, firstPage.get(0).id(), 10, Duration.ZERO);
    assertEquals(1, secondPage.size());
    assertTrue(secondPage.get(0).payloadJson().contains("\"two\""));
    assertEquals(firstPage.get(1).id(), secondPage.get(0).id());
  }
}
