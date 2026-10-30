package fun.fengwk.kkstudio.platform.pluginhost.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    assertTrue(PluginAuthNonce.isValidHandle(first), "an issued handle must be canonical");
  }

  @Test
  void hashIsDeterministicLowercaseSha256AndNeverTheHandle() {
    String handle = PluginAuthNonce.issue();

    String first = PluginAuthNonce.hash(handle);
    String second = PluginAuthNonce.hash(handle);

    assertEquals(first, second, "hashing the same handle must be stable");
    assertTrue(first.matches("[0-9a-f]{64}"), "the digest must be 64 lowercase hex characters");
    assertNotEquals(handle, first, "the raw handle must never be stored or derived verbatim");
    assertNotEquals(PluginAuthNonce.hash(PluginAuthNonce.issue()), first);
  }

  /** 非 canonical handle 在摘要前就被拒绝：长度 43 与 base64url 字符集是唯一合法形态。 */
  @Test
  void rejectsAnythingButCanonicalBase64UrlHandles() {
    assertFalse(PluginAuthNonce.isValidHandle(null), "null must never be a canonical handle");
    assertFalse(PluginAuthNonce.isValidHandle(""));
    assertFalse(PluginAuthNonce.isValidHandle("   "));
    assertFalse(PluginAuthNonce.isValidHandle(" padded "));
    assertFalse(
        PluginAuthNonce.isValidHandle("a".repeat(42)), "42 characters cannot carry 32 bytes");
    assertFalse(
        PluginAuthNonce.isValidHandle("a".repeat(44)), "44 characters cannot be base64url no-pad");
    assertFalse(
        PluginAuthNonce.isValidHandle("a".repeat(10) + "!" + "a".repeat(32)),
        "non base64url characters must be rejected");
    assertFalse(
        PluginAuthNonce.isValidHandle(PluginAuthNonce.issue() + "="),
        "base64 padding must be rejected");

    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash(null));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash(""));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash("   "));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash(" padded "));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash("a".repeat(42)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PluginAuthNonce.hash(PluginAuthNonce.issue() + "="),
        "a padded handle is not one this deployment issued");
  }

  /** 形态判定在长度上先收敛：攻击者可控的任意大字串只付出常数级代价，不先分配或解码。 */
  @Test
  void rejectsOversizedHandlesWithoutScanningThem() {
    String oversized = "a".repeat(4 * 1024 * 1024);

    assertFalse(PluginAuthNonce.isValidHandle(oversized));
    assertThrows(IllegalArgumentException.class, () -> PluginAuthNonce.hash(oversized));
  }
}
