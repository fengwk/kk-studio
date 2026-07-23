package fun.fengwk.kkstudio.core.harness.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

/** 配置验证测试。 */
class HarnessRedisPropertiesTest {

  @Test
  void defaultsAreExposed() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    assertEquals("kk-studio:harness:signal", props.requireSignalChannel());
    assertEquals("kk-studio:harness:realtime:", props.requireRealtimeKeyPrefix());
    assertEquals(1000L, props.requireRealtimeMaxLength());
  }

  @Test
  void blankChannelIsRejected() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setSignalChannel(" ");
    assertThrows(IllegalArgumentException.class, props::requireSignalChannel);
  }

  @Test
  void nullChannelIsRejected() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setSignalChannel(null);
    assertThrows(IllegalArgumentException.class, props::requireSignalChannel);
  }

  @Test
  void blankPrefixIsRejected() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setRealtimeKeyPrefix("");
    assertThrows(IllegalArgumentException.class, props::requireRealtimeKeyPrefix);
  }

  @Test
  void nonPositiveMaxLengthIsRejected() {
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setRealtimeMaxLength(0);
    assertThrows(IllegalArgumentException.class, props::requireRealtimeMaxLength);
    props.setRealtimeMaxLength(-5);
    assertThrows(IllegalArgumentException.class, props::requireRealtimeMaxLength);
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
  void adaptersSnapshotAndValidatePropertiesAtConstruction() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    HarnessRedisProperties props = new HarnessRedisProperties();
    props.setSignalChannel(" ");
    assertThrows(
        IllegalArgumentException.class,
        () -> new RedisActivationNotifier(redisTemplate, props, new ExecutionTargetJsonCodec()));

    props.setSignalChannel("kk-studio:harness:signal");
    props.setRealtimeKeyPrefix("");
    assertThrows(
        IllegalArgumentException.class,
        () -> new RedisRealtimeEventSink(redisTemplate, props, new RealtimeEventJsonCodec()));

    props.setRealtimeKeyPrefix("kk-studio:harness:realtime:");
    props.setRealtimeMaxLength(0L);
    assertThrows(
        IllegalArgumentException.class,
        () -> new RedisRealtimeEventSink(redisTemplate, props, new RealtimeEventJsonCodec()));
  }
}
