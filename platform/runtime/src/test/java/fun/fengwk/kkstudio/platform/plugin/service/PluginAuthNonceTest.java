package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 一次性 attempt 句柄契约：高熵、每次不同、只以领域分隔摘要落库，非法句柄与摘要绝不落地。 */
class PluginAuthNonceTest {

  @Test
  void issuesHighEntropyDistinctHandles() {
    String first = PluginAuthNonce.issue();
    String second = PluginAuthNonce.issue();

    assertNotEquals(first, second, "every prepare must issue a fresh handle");
    assertEquals(43, first.length(), "32 random bytes encode to 43 base64url characters");
    assertTrue(first.matches("[A-Za-z0-9_-]+"), "the handle must be url safe without padding");
  }

  @Test
  void hashIsDeterministicLowercaseSha256AndNeverTheHandle() {
    String handle = "attempt-handle-1";

    String first = PluginAuthNonce.hash(handle);
    String second = PluginAuthNonce.hash(handle);

    assertEquals(first, second, "hashing the same handle must be stable");
    assertTrue(first.matches("[0-9a-f]{64}"), "the digest must be 64 lowercase hex characters");
    assertNotEquals(handle, first, "the raw handle must never be stored or derived verbatim");
    assertNotEquals(PluginAuthNonce.hash("attempt-handle-2"), first);
  }

  @Test
  void rejectsMissingBlankOrPaddedHandles() {
    assertThrows(NullPointerException.class, () -> PluginAuthNonce.hash(null));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash(""));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash("   "));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash(" padded "));
  }
}
