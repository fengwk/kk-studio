package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** PluginCredentialMaterial 是 Plugin 交还的待加密材料，测试锁定字段校验与 secret 去敏输出。 */
class PluginCredentialMaterialTest {

  private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant NEXT_REFRESH_AT = Instant.parse("2029-12-31T00:00:00Z");

  private static PluginCredentialMaterial material(String region, String payloadJson) {
    return new PluginCredentialMaterial(region, EXPIRES_AT, NEXT_REFRESH_AT, payloadJson);
  }

  @Test
  void acceptsOpaqueObjectPayload() {
    String payloadJson = "{\"accessToken\":\"secret\"}";

    PluginCredentialMaterial material = material("CN", payloadJson);

    assertEquals("CN", material.region());
    assertEquals(EXPIRES_AT, material.expiresAt());
    assertEquals(NEXT_REFRESH_AT, material.nextRefreshAt());
    assertEquals(payloadJson, material.payloadJson());
  }

  @Test
  void toStringRedactsSecretPayload() {
    String text = material("CN", "{\"accessToken\":\"secret\"}").toString();

    assertTrue(text.contains("<redacted>"));
    assertFalse(text.contains("secret"));
  }

  @Test
  void rejectsBlankOrPaddedRegion() {
    assertThrows(IllegalArgumentException.class, () -> material(null, "{}"));
    assertThrows(IllegalArgumentException.class, () -> material("", "{}"));
    assertThrows(IllegalArgumentException.class, () -> material(" CN ", "{}"));
  }

  /** 混合三元组（region 与两个时刻不全存在）必须固定拒绝，绝不接受半个交互凭据。 */
  /** 静态密钥形态没有 region/过期/刷新，且 interactive() 如实为 false。 */
  @Test
  void staticKeyMaterialHasNoRouteMetadata() {
    PluginCredentialMaterial material = PluginCredentialMaterial.staticKey("{\"apiKey\":\"k\"}");

    assertNull(material.region());
    assertNull(material.expiresAt());
    assertNull(material.nextRefreshAt());
    assertFalse(material.interactive());
    assertTrue(material("CN", "{}").interactive());
  }

  @Test
  void rejectsMixedRouteMetadata() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialMaterial("CN", null, NEXT_REFRESH_AT, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialMaterial("CN", EXPIRES_AT, null, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCredentialMaterial(null, EXPIRES_AT, NEXT_REFRESH_AT, "{}"));
  }

  @Test
  void rejectsBlankOrNonObjectPayload() {
    assertThrows(IllegalArgumentException.class, () -> material("CN", " "));
    assertThrows(IllegalArgumentException.class, () -> material("CN", "[1,2,3]"));
    assertThrows(IllegalArgumentException.class, () -> material("CN", "{not json"));
  }

  @Test
  void rejectsPayloadBeyondUtf8ByteLimit() {
    String oversized =
        "{\"a\":\"" + "x".repeat(PluginCredentialMaterial.MAX_PAYLOAD_UTF8_BYTES) + "\"}";
    assertTrue(oversized.getBytes(StandardCharsets.UTF_8).length > 64 * 1024);

    assertThrows(IllegalArgumentException.class, () -> material("CN", oversized));
  }
}
