package fun.fengwk.kkstudio.platform.plugin.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.support.PluginHttpSupport.ResponseTooLargeException;

import java.io.ByteArrayInputStream;

class PluginHttpSupportTest {

  /** 重复键和尾随 JSON 都必须拒绝，同时不修改原 mapper。 */
  @Test
  void strictJsonRejectsAmbiguousInput() {
    ObjectMapper base = new ObjectMapper();
    ObjectMapper strict = PluginHttpSupport.strictJson(base);
    assertEquals(1, strict.readTree("{\"x\":1}").get("x").intValue());
    assertThrows(JacksonException.class, () -> strict.readTree("{\"x\":1,\"x\":2}"));
    assertThrows(JacksonException.class, () -> strict.readTree("{} {}"));
    assertEquals(2, base.readTree("{\"x\":1,\"x\":2}").get("x").intValue());
  }

  /** 精确上限可读，超限只额外消费一字节，错误不含正文。 */
  @Test
  void readsAtMostLimitPlusOneByte() throws Exception {
    assertArrayEquals(
        new byte[] {1, 2},
        PluginHttpSupport.readBounded(new ByteArrayInputStream(new byte[] {1, 2}), 2));
    ByteArrayInputStream tooLong = new ByteArrayInputStream(new byte[] {1, 2, 3, 4});
    assertThrows(ResponseTooLargeException.class, () -> PluginHttpSupport.readBounded(tooLong, 2));
    assertEquals(1, tooLong.available());
    assertArrayEquals(
        new byte[0], PluginHttpSupport.readBounded(new ByteArrayInputStream(new byte[0]), 0));
    assertThrows(IllegalArgumentException.class, () -> PluginHttpSupport.readBounded(tooLong, -1));
  }

  /** Bearer 输入校验不得泄露凭据，作废只使用本次的快照。 */
  @Test
  void bearerAndRejectionAreScopedAndSanitized() {
    String secret = "secret-marker";
    assertEquals("Bearer " + secret, PluginHttpSupport.bearer(secret));
    for (String invalid : new String[] {"", "secret-marker\n", " secret-marker", "密钥"}) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> PluginHttpSupport.bearer(invalid));
      assertFalse(error.getMessage().contains(secret));
    }
    assertThrows(IllegalArgumentException.class, () -> PluginHttpSupport.bearer(null));
    assertTrue(PluginHttpSupport.isAuthRejected(401));
    assertTrue(PluginHttpSupport.isAuthRejected(403));
    assertFalse(PluginHttpSupport.isAuthRejected(500));

    PluginCredentialSnapshot snapshot = new PluginCredentialSnapshot("test", null, null, 1, "{}");
    PluginCredentialAccess access =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            return snapshot;
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot used, String error) {
            assertSame(snapshot, used);
            assertEquals("rejected", error);
            throw new IllegalStateException(secret);
          }
        };
    PluginHttpSupport.rejectUsed(access, snapshot, "rejected");
  }
}
