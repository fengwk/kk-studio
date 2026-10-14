package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link PluginCatalogManifestJson} 的确定性编码与严格解码契约测试。
 *
 * <p>覆盖 pluginId 排序与固定字段顺序、未初始化形态、往返相等，以及对重复字段、尾随内容、未知/缺失字段、错误类型、非正/溢出 revision、非法指纹与非法 pluginId
 * 的固定拒绝。
 */
class PluginCatalogManifestJsonTest {

  private static final String FINGERPRINT_A = "a".repeat(64);
  private static final String FINGERPRINT_B = "b".repeat(64);
  private static final String FINGERPRINT_C = "c".repeat(64);
  private static final String FINGERPRINT_D = "d".repeat(64);
  private static final String INVALID_MESSAGE = "stored plugin catalog manifest is invalid";
  private static final String GENERATION = "11111111-1111-1111-1111-111111111111";
  private static final String CONTEXT = "e".repeat(64);

  /** 版本长度上限必须与 PluginDescriptor 一致：64 字符可读回，65 字符被固定拒绝。 */
  @Test
  void codeVersionBoundMatchesThePluginDescriptorBound() {
    String atBound = "x".repeat(64);
    PluginCatalogManifest decoded =
        PluginCatalogManifestJson.decode(
            entryDocument("alpha.plugin", "1", atBound, FINGERPRINT_A, FINGERPRINT_B));
    assertEquals(atBound, decoded.plugins().get("alpha.plugin").pluginCodeVersion());

    assertRejected(
        entryDocument("alpha.plugin", "1", "x".repeat(65), FINGERPRINT_A, FINGERPRINT_B));
  }

  /** 编码按 pluginId 升序、entry 固定字段顺序输出，往返解码与原始 manifest 相等。 */
  @Test
  void encodesInCanonicalOrderAndRoundTrips() {
    Map<String, PluginCatalogManifest.Entry> plugins = new LinkedHashMap<>();
    plugins.put("beta.plugin", entry(2, "1.1", FINGERPRINT_B, FINGERPRINT_C));
    plugins.put("alpha.plugin", entry(1, "1.0", FINGERPRINT_A, FINGERPRINT_D));
    PluginCatalogManifest manifest = new PluginCatalogManifest(plugins, FINGERPRINT_D);

    String json = PluginCatalogManifestJson.encode(manifest);

    assertEquals(
        "{\"plugins\":{"
            + "\"alpha.plugin\":{\"configRevision\":1,\"pluginCodeVersion\":\"1.0\","
            + "\"schemaFingerprint\":\""
            + FINGERPRINT_A
            + "\",\"capabilityFingerprint\":\""
            + FINGERPRINT_D
            + "\",\"authGeneration\":\""
            + GENERATION
            + "\",\"contextFingerprint\":\""
            + CONTEXT
            + "\"},"
            + "\"beta.plugin\":{\"configRevision\":2,\"pluginCodeVersion\":\"1.1\","
            + "\"schemaFingerprint\":\""
            + FINGERPRINT_B
            + "\",\"capabilityFingerprint\":\""
            + FINGERPRINT_C
            + "\",\"authGeneration\":\""
            + GENERATION
            + "\",\"contextFingerprint\":\""
            + CONTEXT
            + "\"}},"
            + "\"catalogFingerprint\":\""
            + FINGERPRINT_D
            + "\"}",
        json);
    assertEquals(manifest, PluginCatalogManifestJson.decode(json));
  }

  /** 未初始化形态（epoch 0 的 manifest）编码 null 指纹并可无损读回。 */
  @Test
  void roundTripsUninitializedManifest() {
    PluginCatalogManifest manifest = PluginCatalogManifest.uninitialized();

    String json = PluginCatalogManifestJson.encode(manifest);

    assertEquals("{\"plugins\":{},\"catalogFingerprint\":null}", json);
    PluginCatalogManifest decoded = PluginCatalogManifestJson.decode(json);
    assertEquals(manifest, decoded);
    assertNull(decoded.catalogFingerprint());
    assertTrue(decoded.plugins().isEmpty());
  }

  @Test
  void rejectsMalformedDocuments() {
    String validEntry = entryJson(1);
    String suffix = ",\"catalogFingerprint\":\"" + FINGERPRINT_C + "\"}";
    String valid = "{\"plugins\":{\"alpha.plugin\":" + validEntry + "}" + suffix;
    assertEquals(FINGERPRINT_C, PluginCatalogManifestJson.decode(valid).catalogFingerprint());

    assertRejected(null);
    assertRejected("");
    assertRejected("[]");
    assertRejected("null");
    assertRejected("{\"plugins\":{}}");
    assertRejected("{\"plugins\":{},\"catalogFingerprint\":null,\"extra\":1}");
    assertRejected("{\"plugins\":{},\"plugins\":{},\"catalogFingerprint\":null}");
    assertRejected("{\"plugins\":{},\"catalogFingerprint\":null} {}");
    assertRejected("{\"plugins\":[],\"catalogFingerprint\":null}");
    assertRejected("{\"plugins\":{},\"catalogFingerprint\":1}");
    assertRejected("{\"plugins\":{},\"catalogFingerprint\":\"" + "A".repeat(64) + "\"}");
    assertRejected("{\"plugins\":{\"alpha.plugin\":[]}" + suffix);
    assertRejected(valid.replace(",\"capabilityFingerprint\":\"" + FINGERPRINT_B + "\"", ""));
    assertRejected(valid.replace("\"configRevision\":1", "\"configRevision\":1,\"extra\":1"));
    assertRejected(
        valid.replace("\"configRevision\":1", "\"configRevision\":1,\"configRevision\":1"));
    assertRejected(valid + " {\"x\":1}");
    assertRejected(
        "{\"plugins\":{\"alpha.plugin\":"
            + validEntry
            + ",\"alpha.plugin\":"
            + validEntry
            + "}"
            + suffix);
  }

  @Test
  void rejectsInvalidRevisions() {
    assertRejectedWithRevision("0");
    assertRejectedWithRevision("-1");
    assertRejectedWithRevision("1.5");
    assertRejectedWithRevision("1.0");
    assertRejectedWithRevision("1e0");
    assertRejectedWithRevision("\"1\"");
    assertRejectedWithRevision("9223372036854775808");
    assertRejectedWithRevision("null");
  }

  @Test
  void rejectsInvalidEntryText() {
    assertRejected(entryDocument("Bad.Plugin", "1", "1.0", FINGERPRINT_A, FINGERPRINT_B));
    assertRejected(entryDocument("alpha.plugin", "1", " ", FINGERPRINT_A, FINGERPRINT_B));
    assertRejected(entryDocument("alpha.plugin", "1", "1.0", "A".repeat(64), FINGERPRINT_B));
    assertRejected(entryDocument("alpha.plugin", "1", "1.0", FINGERPRINT_A, "b".repeat(63)));
    assertRejected(entryDocument("alpha.plugin", "1", "1.0", FINGERPRINT_A, "null"));
  }

  /** entry 必须携带认证代际与上下文指纹：缺失、非 UUID 或空值都必须固定拒绝。 */
  @Test
  void rejectsEntryWithoutCredentialIdentity() {
    assertRejected(validEntryDocument().replace(",\"authGeneration\":\"" + GENERATION + "\"", ""));
    assertRejected(validEntryDocument().replace(",\"contextFingerprint\":\"" + CONTEXT + "\"", ""));
    assertRejected(validEntryDocument().replace(GENERATION, "not-a-uuid"));
    assertRejected(validEntryDocument().replace(CONTEXT, " "));
  }

  /** 超过 1 MiB UTF-8 上限的 manifest 必须在编码前中止；读回同样固定拒绝。 */
  @Test
  void rejectsOversizedManifest() {
    Map<String, PluginCatalogManifest.Entry> plugins = new LinkedHashMap<>();
    for (int index = 0; index < 8000; index++) {
      plugins.put("plugin" + index, entry(1, "1.0", FINGERPRINT_A, FINGERPRINT_B));
    }
    PluginCatalogManifest manifest = new PluginCatalogManifest(plugins, FINGERPRINT_C);

    assertThrows(IllegalArgumentException.class, () -> PluginCatalogManifestJson.encode(manifest));

    StringBuilder oversized = new StringBuilder("{\"plugins\":{");
    for (int index = 0; index < 8000; index++) {
      if (index > 0) {
        oversized.append(',');
      }
      oversized.append('"').append("plugin").append(index).append("\":").append(entryJson(1));
    }
    oversized.append("},\"catalogFingerprint\":null}");
    assertRejected(oversized.toString());
  }

  /** 失败信息必须固定，不回显原始 JSON 内容。 */
  @Test
  void failureMessageDoesNotEchoInput() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalogManifestJson.decode(
                    "{\"plugins\":{},\"catalogFingerprint\":\"secret\"}"));
    assertEquals(INVALID_MESSAGE, error.getMessage());
    assertNull(error.getCause());
  }

  private static String validEntryDocument() {
    return entryDocument("alpha.plugin", "1", "1.0", FINGERPRINT_A, FINGERPRINT_B);
  }

  private static void assertRejectedWithRevision(String revision) {
    assertRejected(entryDocument("alpha.plugin", revision, "1.0", FINGERPRINT_A, FINGERPRINT_B));
  }

  private static String entryDocument(
      String pluginId,
      String revision,
      String codeVersion,
      String schemaFingerprint,
      String capabilityFingerprint) {
    return "{\"plugins\":{\""
        + pluginId
        + "\":{\"configRevision\":"
        + revision
        + ",\"pluginCodeVersion\":"
        + (codeVersion.startsWith("\"") || codeVersion.equals("null")
            ? codeVersion
            : "\"" + codeVersion + "\"")
        + ",\"schemaFingerprint\":"
        + quoteUnlessNull(schemaFingerprint)
        + ",\"capabilityFingerprint\":"
        + quoteUnlessNull(capabilityFingerprint)
        + ",\"authGeneration\":\""
        + GENERATION
        + "\",\"contextFingerprint\":\""
        + CONTEXT
        + "\"}},\"catalogFingerprint\":\""
        + FINGERPRINT_C
        + "\"}";
  }

  private static String quoteUnlessNull(String value) {
    return value.equals("null") ? "null" : "\"" + value + "\"";
  }

  private static String entryJson(long revision) {
    return "{\"configRevision\":"
        + revision
        + ",\"pluginCodeVersion\":\"1.0\",\"schemaFingerprint\":\""
        + FINGERPRINT_A
        + "\",\"capabilityFingerprint\":\""
        + FINGERPRINT_B
        + "\",\"authGeneration\":\""
        + GENERATION
        + "\",\"contextFingerprint\":\""
        + CONTEXT
        + "\"}";
  }

  private static void assertRejected(String json) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> PluginCatalogManifestJson.decode(json));
    assertEquals(INVALID_MESSAGE, error.getMessage());
    assertNull(error.getCause(), "rejection must not attach a raw cause");
  }

  private static PluginCatalogManifest.Entry entry(
      long revision, String codeVersion, String schemaFingerprint, String capabilityFingerprint) {
    return new PluginCatalogManifest.Entry(
        revision,
        codeVersion,
        schemaFingerprint,
        capabilityFingerprint,
        UUID.fromString(GENERATION),
        CONTEXT);
  }
}
