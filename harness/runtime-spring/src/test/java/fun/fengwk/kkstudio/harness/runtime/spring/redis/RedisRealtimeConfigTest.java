package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** RedisRealtimeConfig 的默认值、校验与 key 拼接契约。 */
class RedisRealtimeConfigTest {

  @Test
  void defaultsUseDocumentedPrefixAndMaxLength() {
    RedisRealtimeConfig config = new RedisRealtimeConfig();
    assertEquals("kk-studio:harness:realtime:", config.prefix());
    assertEquals(5_000, config.maxLength());
  }

  @Test
  void blankOrNullPrefixIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new RedisRealtimeConfig(null, 5_000));
    assertThrows(IllegalArgumentException.class, () -> new RedisRealtimeConfig("  ", 5_000));
  }

  @Test
  void nonPositiveMaxLengthIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new RedisRealtimeConfig("p:", 0));
    assertThrows(IllegalArgumentException.class, () -> new RedisRealtimeConfig("p:", -1));
  }

  @Test
  void keyIsPrefixPlusUuidThreadId() {
    RedisRealtimeConfig config = new RedisRealtimeConfig("kk-studio:harness:realtime:", 5000);
    assertEquals("kk-studio:harness:realtime:" + id(42L), config.key(id(42L)));
  }

  @Test
  void keyRejectsNullThreadId() {
    RedisRealtimeConfig config = new RedisRealtimeConfig();
    assertThrows(NullPointerException.class, () -> config.key(null));
  }
}
