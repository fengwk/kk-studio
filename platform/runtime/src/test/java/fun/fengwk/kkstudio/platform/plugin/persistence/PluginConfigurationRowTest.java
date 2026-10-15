package fun.fengwk.kkstudio.platform.plugin.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * {@link PluginConfigurationRow} 的不可变记录门禁与值语义测试。
 *
 * <p>覆盖合法读取、字段访问与值语义，以及 pluginId、revision、code version、两份指纹与普通值文本的逐项固定拒绝；64 字符版本必须被接受， 证明门禁与 {@link
 * PluginCatalogManifest.Entry} / {@code PluginDescriptor} 同界。
 */
class PluginConfigurationRowTest {

  private static final String PLUGIN_ID = "alpha.plugin";
  private static final String SCHEMA_FINGERPRINT = "a".repeat(64);
  private static final String CAPABILITY_FINGERPRINT = "b".repeat(64);
  private static final String VALUES = "{\"enabled\":true}";
  private static final String CONTEXT = "c".repeat(64);
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void exposesEveryFieldWithValueSemantics() {
    PluginConfigurationRow row = row(PLUGIN_ID, 3, "1.0", VALUES);
    PluginConfigurationRow same = row(PLUGIN_ID, 3, "1.0", VALUES);

    assertEquals(PLUGIN_ID, row.pluginId());
    assertEquals(3, row.revision());
    assertEquals("1.0", row.codeVersion());
    assertEquals(SCHEMA_FINGERPRINT, row.schemaFingerprint());
    assertEquals(CAPABILITY_FINGERPRINT, row.capabilityFingerprint());
    assertEquals(GENERATION, row.authGeneration());
    assertEquals(CONTEXT, row.contextFingerprint());
    assertEquals(List.of(), row.configuredSecretFields());
    assertEquals(VALUES, row.valuesJson());
    assertEquals(row, same);
    assertEquals(row.hashCode(), same.hashCode());
    assertNotEquals(row, row(PLUGIN_ID, 4, "1.0", VALUES));
    assertTrue(row.toString().contains(PLUGIN_ID));
  }

  /** 64 字符版本与 PluginDescriptor.MAX_VERSION_CHARS 同界，必须被接受。 */
  @Test
  void acceptsThePluginDescriptorVersionBound() {
    assertEquals("x".repeat(64), row(PLUGIN_ID, 1, "x".repeat(64), VALUES).codeVersion());
  }

  @Test
  void rejectsInvalidIdentityAndRevision() {
    assertThrows(NullPointerException.class, () -> row(null, 1, "1.0", VALUES));
    assertThrows(IllegalArgumentException.class, () -> row("Alpha.Plugin", 1, "1.0", VALUES));
    assertThrows(IllegalArgumentException.class, () -> row(".alpha", 1, "1.0", VALUES));
    assertThrows(IllegalArgumentException.class, () -> row("p".repeat(65), 1, "1.0", VALUES));
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 0, "1.0", VALUES));
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, -1, "1.0", VALUES));
  }

  @Test
  void rejectsInvalidCodeVersion() {
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, null, VALUES));
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, "  ", VALUES));
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, " 1.0 ", VALUES));
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, "x".repeat(65), VALUES));
  }

  @Test
  void rejectsInvalidFingerprints() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                null,
                CAPABILITY_FINGERPRINT,
                GENERATION,
                CONTEXT,
                List.of(),
                VALUES));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                "A".repeat(64),
                CAPABILITY_FINGERPRINT,
                GENERATION,
                CONTEXT,
                List.of(),
                VALUES));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                "a".repeat(63),
                CAPABILITY_FINGERPRINT,
                GENERATION,
                CONTEXT,
                List.of(),
                VALUES));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                SCHEMA_FINGERPRINT,
                "z".repeat(64),
                GENERATION,
                CONTEXT,
                List.of(),
                VALUES));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                SCHEMA_FINGERPRINT,
                null,
                GENERATION,
                CONTEXT,
                List.of(),
                VALUES));
  }

  /** 认证身份与已录入秘密字段名各有独立门禁：缺代际、指纹形状错与含 null 字段名都必须固定拒绝。 */
  @Test
  void rejectsInvalidCredentialIdentityAndConfiguredFields() {
    assertThrows(
        NullPointerException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                SCHEMA_FINGERPRINT,
                CAPABILITY_FINGERPRINT,
                null,
                CONTEXT,
                List.of(),
                VALUES));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                SCHEMA_FINGERPRINT,
                CAPABILITY_FINGERPRINT,
                GENERATION,
                "A".repeat(64),
                List.of(),
                VALUES));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                SCHEMA_FINGERPRINT,
                CAPABILITY_FINGERPRINT,
                GENERATION,
                CONTEXT,
                Arrays.asList("apiKey", null),
                VALUES));
    assertEquals(
        List.of("apiKey"),
        new PluginConfigurationRow(
                PLUGIN_ID,
                1,
                "1.0",
                SCHEMA_FINGERPRINT,
                CAPABILITY_FINGERPRINT,
                GENERATION,
                CONTEXT,
                List.of("apiKey", "apiKey"),
                VALUES)
            .configuredSecretFields(),
        "configured secret field names must be deduplicated");
  }

  @Test
  void rejectsInvalidValuesText() {
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, "1.0", "[]"));
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, "1.0", "{} {}"));
    assertThrows(
        IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, "1.0", "{\"a\":1,\"a\":2}"));
    assertThrows(IllegalArgumentException.class, () -> row(PLUGIN_ID, 1, "1.0", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> row(PLUGIN_ID, 1, "1.0", "{\"a\":\"" + "x".repeat(70_000) + "\"}"));
  }

  private static PluginConfigurationRow row(
      String pluginId, long revision, String codeVersion, String valuesJson) {
    return new PluginConfigurationRow(
        pluginId,
        revision,
        codeVersion,
        SCHEMA_FINGERPRINT,
        CAPABILITY_FINGERPRINT,
        GENERATION,
        CONTEXT,
        List.of(),
        valuesJson);
  }
}
