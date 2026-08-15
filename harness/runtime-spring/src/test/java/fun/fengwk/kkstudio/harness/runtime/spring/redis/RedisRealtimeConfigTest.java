package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** RedisRealtimeConfig 的默认值、校验与 channel 拼接契约。 */
class RedisRealtimeConfigTest {

  @Test
  void defaultsUseDocumentedPrefix() {
    RedisRealtimeConfig config = new RedisRealtimeConfig();
    assertEquals("kk-studio:harness:realtime:", config.prefix());
  }

  @Test
  void blankOrNullPrefixIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new RedisRealtimeConfig(null));
    assertThrows(IllegalArgumentException.class, () -> new RedisRealtimeConfig("  "));
  }

  @Test
  void channelIsPrefixPlusUuidThreadId() {
    RedisRealtimeConfig config = new RedisRealtimeConfig("kk-studio:harness:realtime:");
    assertEquals("kk-studio:harness:realtime:" + id(42L), config.channel(id(42L)));
  }

  @Test
  void channelRejectsNullThreadId() {
    RedisRealtimeConfig config = new RedisRealtimeConfig();
    assertThrows(NullPointerException.class, () -> config.channel(null));
  }
}
