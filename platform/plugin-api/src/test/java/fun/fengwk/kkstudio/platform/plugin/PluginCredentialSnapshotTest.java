package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** PluginCredentialSnapshot 承载密文、版本与认证身份，测试锁定数组防御性拷贝、相等语义、两种材料形态与去敏输出。 */
class PluginCredentialSnapshotTest {

  private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String CONTEXT =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void copiesEncryptedPayloadDefensively() {
    byte[] encrypted = {1, 2, 3};
    PluginCredentialSnapshot snapshot = snapshot(encrypted);

    encrypted[0] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, snapshot.encryptedPayload());

    byte[] exposed = snapshot.encryptedPayload();
    exposed[0] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, snapshot.encryptedPayload());
  }

  @Test
  void equalityComparesEncryptedPayloadByContent() {
    PluginCredentialSnapshot first = snapshot(new byte[] {1, 2, 3});
    PluginCredentialSnapshot same = snapshot(new byte[] {1, 2, 3});
    PluginCredentialSnapshot other = snapshot(new byte[] {4, 5, 6});

    assertEquals(first, same);
    assertEquals(first.hashCode(), same.hashCode());
    assertNotEquals(first, other);
    assertTrue(first.equals(first));
    assertFalse(first.equals(null));
    assertFalse(first.equals("not-a-snapshot"));
  }

  /** 认证代际参与相等语义：同 pluginId/version/密文但代际不同必须是不同快照。 */
  @Test
  void equalityIncludesAuthGeneration() {
    PluginCredentialSnapshot first = snapshot(new byte[] {1, 2, 3});
    PluginCredentialSnapshot otherGeneration =
        new PluginCredentialSnapshot(
            "acme.plugin",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            CONTEXT,
            "CN",
            EXPIRES_AT,
            3,
            new byte[] {1, 2, 3},
            "{\"secret\":true}");

    assertNotEquals(first, otherGeneration);
  }

  @Test
  void toStringRedactsPayloadAndEncryptedBytes() {
    PluginCredentialSnapshot snapshot = snapshot(new byte[] {1, 2, 3});

    String text = snapshot.toString();

    assertTrue(text.contains("<redacted>"));
    assertFalse(text.contains("{\"secret\":true}"));
  }

  @Test
  void rejectsMissingFieldsAndNegativeVersion() {
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginCredentialSnapshot(
                null, GENERATION, CONTEXT, "CN", EXPIRES_AT, 1, new byte[] {1}, "{}"));
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginCredentialSnapshot(
                "p", GENERATION, CONTEXT, "CN", EXPIRES_AT, 1, null, "{}"));
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginCredentialSnapshot(
                "p", null, CONTEXT, "CN", EXPIRES_AT, 1, new byte[] {1}, "{}"));
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginCredentialSnapshot(
                "p", GENERATION, null, "CN", EXPIRES_AT, 1, new byte[] {1}, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCredentialSnapshot(
                "p", GENERATION, CONTEXT, "CN", EXPIRES_AT, -1, new byte[] {1}, "{}"));
  }

  /** 交互凭据的 region 与 expiresAt 必须同时存在或同时缺失：绝不接受混合三元组。 */
  @Test
  void rejectsMixedRouteMetadata() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCredentialSnapshot(
                "p", GENERATION, CONTEXT, "CN", null, 1, new byte[] {1}, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCredentialSnapshot(
                "p", GENERATION, CONTEXT, null, EXPIRES_AT, 1, new byte[] {1}, "{}"));
  }

  /** 静态密钥快照没有 region 与过期，仍携带认证代际与上下文指纹。 */
  @Test
  void staticKeySnapshotCarriesIdentityWithoutRouteMetadata() {
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(
            "acme.plugin",
            GENERATION,
            CONTEXT,
            null,
            null,
            0,
            new byte[] {1},
            "{\"apiKey\":\"k\"}");

    assertFalse(snapshot.interactive());
    assertEquals(GENERATION, snapshot.reference().authGeneration());
    assertEquals(CONTEXT, snapshot.reference().contextFingerprint());
  }

  private static PluginCredentialSnapshot snapshot(byte[] encryptedPayload) {
    return new PluginCredentialSnapshot(
        "acme.plugin",
        GENERATION,
        CONTEXT,
        "CN",
        EXPIRES_AT,
        3,
        encryptedPayload,
        "{\"secret\":true}");
  }
}
