package fun.fengwk.kkstudio.core.ai.runtime.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

/** Redis realtime projection configuration validation. */
class HarnessRedisPropertiesTest {

  @Test
  void defaultsAreExposed() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    assertEquals("kk-studio:harness:realtime:", props.requireRealtimeKeyPrefix());
  }

  @Test
  void blankPrefixIsRejected() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setRealtimeKeyPrefix("");
    assertThrows(IllegalArgumentException.class, props::requireRealtimeKeyPrefix);
  }

  @Test
  void realtimeKeyConcatenatesPrefixAndThreadId() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setRealtimeKeyPrefix("kk-studio:harness:realtime:");
    assertEquals("kk-studio:harness:realtime:7", props.realtimeKey(7L));
  }

  @Test
  void realtimeKeyRejectsNonPositiveThreadId() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    assertThrows(IllegalArgumentException.class, () -> props.realtimeKey(0L));
    assertThrows(IllegalArgumentException.class, () -> props.realtimeKey(-1L));
  }

  @Test
  void adaptersValidateRedisTransportPropertiesAtConstruction() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setRealtimeKeyPrefix("");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisRealtimeEventSink(
                () -> redisTemplate, props, new RealtimeEventJsonCodec(), () -> 5_000L));
  }
}
