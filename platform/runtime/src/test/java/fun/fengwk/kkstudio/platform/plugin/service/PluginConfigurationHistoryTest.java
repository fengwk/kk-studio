package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginConfigurationRepository;

import java.util.List;
import java.util.UUID;

/** 权威配置历史的严格读取契约：缺失或身份漂移一律 fail closed，绝不退回空字段或旧值。 */
class PluginConfigurationHistoryTest {

  private static final String PLUGIN_ID = "history.plugin";
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String CONTEXT = "a1".repeat(32);
  private static final long REVISION = 3L;

  private InMemoryPluginConfigurationRepository repository;

  @BeforeEach
  void setUp() {
    repository = new InMemoryPluginConfigurationRepository();
  }

  /** 身份逐字段一致时返回历史行与普通值。 */
  @Test
  void returnsMatchingHistory() {
    PluginCatalogManifest.Entry entry = entry("1.0", "b".repeat(64), GENERATION, CONTEXT);
    repository.putHistory(row(REVISION, entry, List.of("apiKey"), "{\"enabled\":true}"));

    PluginConfigurationRow history =
        PluginConfigurationHistory.require(repository, PLUGIN_ID, entry);

    assertEquals(REVISION, history.revision());
    assertEquals(List.of("apiKey"), history.configuredSecretFields());
    assertEquals(
        "{\"enabled\":true}",
        PluginConfigurationHistory.requireValues(repository, PLUGIN_ID, entry));
  }

  /** 历史行缺失：不能当成「未录入」，也不能返回空配置。 */
  @Test
  void missingHistoryFailsClosed() {
    PluginCatalogManifest.Entry entry = entry("1.0", "b".repeat(64), GENERATION, CONTEXT);

    IllegalStateException missing =
        assertThrows(
            IllegalStateException.class,
            () -> PluginConfigurationHistory.require(repository, PLUGIN_ID, entry));
    assertTrue(missing.getMessage().contains("history is missing"), missing.getMessage());
    assertThrows(
        IllegalStateException.class,
        () -> PluginConfigurationHistory.requireValues(repository, PLUGIN_ID, entry));
  }

  /** 身份任一字段漂移都必须 fail closed：代码版本、schema 指纹、能力指纹、代际、上下文指纹。 */
  @Test
  void everyIdentityDriftFailsClosed() {
    PluginCatalogManifest.Entry expected = entry("1.0", "b".repeat(64), GENERATION, CONTEXT);

    List<PluginConfigurationRow> drifted =
        List.of(
            row(REVISION, entry("9.9", "b".repeat(64), GENERATION, CONTEXT), List.of(), "{}"),
            row(REVISION, entry("1.0", "c".repeat(64), GENERATION, CONTEXT), List.of(), "{}"),
            row(
                REVISION,
                entry("1.0", "b".repeat(64), UUID.randomUUID(), CONTEXT),
                List.of(),
                "{}"),
            row(
                REVISION,
                entry("1.0", "b".repeat(64), GENERATION, "d".repeat(64)),
                List.of(),
                "{}"));

    for (PluginConfigurationRow row : drifted) {
      repository.putHistory(row);
      assertThrows(
          IllegalStateException.class,
          () -> PluginConfigurationHistory.require(repository, PLUGIN_ID, expected),
          () -> "history identity drift must fail closed: " + row);
      repository.removeHistory(PLUGIN_ID, REVISION);
    }
  }

  /** 修订号本身也必须一致，不能读到另一个 revision 的行。 */
  @Test
  void revisionMismatchFailsClosed() {
    PluginCatalogManifest.Entry expected = entry("1.0", "b".repeat(64), GENERATION, CONTEXT);
    repository.putHistory(row(REVISION - 1, expected, List.of(), "{}"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> PluginConfigurationHistory.require(repository, PLUGIN_ID, expected));
    assertTrue(error.getMessage().contains("history is missing"), error.getMessage());
  }

  private static PluginCatalogManifest.Entry entry(
      String codeVersion, String schemaFingerprint, UUID generation, String context) {
    return new PluginCatalogManifest.Entry(
        REVISION, codeVersion, schemaFingerprint, "d".repeat(64), generation, context);
  }

  private static PluginConfigurationRow row(
      long revision,
      PluginCatalogManifest.Entry entry,
      List<String> configuredSecretFields,
      String valuesJson) {
    return new PluginConfigurationRow(
        PLUGIN_ID,
        revision,
        entry.pluginCodeVersion(),
        entry.schemaFingerprint(),
        entry.capabilityFingerprint(),
        entry.authGeneration(),
        entry.contextFingerprint(),
        configuredSecretFields,
        valuesJson);
  }
}
