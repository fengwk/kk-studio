package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

/** {@link PluginCatalogRecord} 的 epoch 与 manifest 一致性门禁测试。 */
class PluginCatalogRecordTest {

  private static final String FINGERPRINT = "a".repeat(64);
  private static final String CONTEXT = "b".repeat(64);
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void epochZeroMustBeTheUninitializedManifest() {
    PluginCatalogRecord record = new PluginCatalogRecord(0, PluginCatalogManifest.uninitialized());
    assertEquals(0, record.epoch());
    assertEquals(PluginCatalogManifest.uninitialized(), record.manifest());

    assertThrows(
        IllegalArgumentException.class, () -> new PluginCatalogRecord(0, initializedManifest()));
  }

  @Test
  void initializedEpochMustDeclareAFingerprint() {
    assertEquals(3, new PluginCatalogRecord(3, initializedManifest()).epoch());

    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCatalogRecord(1, PluginCatalogManifest.uninitialized()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCatalogRecord(-1, PluginCatalogManifest.uninitialized()));
  }

  /** entry 的 configRevision 就是撰写它的全局 epoch，领先 epoch 说明记录已损坏。 */
  @Test
  void entryRevisionMustNotExceedTheEpoch() {
    PluginCatalogManifest manifest =
        new PluginCatalogManifest(
            Map.of(
                "alpha.plugin",
                new PluginCatalogManifest.Entry(
                    4, "1.0", FINGERPRINT, FINGERPRINT, GENERATION, CONTEXT)),
            FINGERPRINT);

    assertEquals(4, new PluginCatalogRecord(4, manifest).epoch());
    assertThrows(IllegalArgumentException.class, () -> new PluginCatalogRecord(3, manifest));
  }

  /** manifest 与 entry 的身份门禁：指纹必须是 64 位小写 SHA-256，修订号为正，代际非空。 */
  @Test
  void manifestAndEntryRejectInvalidIdentity() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCatalogManifest(Map.of(), "XYZ"),
        "an invalid catalogFingerprint must be rejected");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginCatalogManifest(
                Map.of(
                    "alpha.plugin",
                    new PluginCatalogManifest.Entry(
                        1, "1.0", FINGERPRINT, FINGERPRINT, GENERATION, CONTEXT)),
                null),
        "plugins without a catalogFingerprint must be rejected");

    assertThrows(
        IllegalArgumentException.class,
        () -> entry(0, "1.0", FINGERPRINT, FINGERPRINT, GENERATION, CONTEXT),
        "configRevision must be positive");
    assertThrows(
        IllegalArgumentException.class,
        () -> entry(1, "1.0", FINGERPRINT, FINGERPRINT, GENERATION, null),
        "contextFingerprint must be a 64-char lowercase SHA-256");
    assertThrows(
        NullPointerException.class,
        () -> entry(1, "1.0", FINGERPRINT, FINGERPRINT, null, CONTEXT),
        "every entry must carry a real auth generation");
  }

  private static PluginCatalogManifest.Entry entry(
      long configRevision,
      String codeVersion,
      String schemaFingerprint,
      String capabilityFingerprint,
      UUID authGeneration,
      String contextFingerprint) {
    return new PluginCatalogManifest.Entry(
        configRevision,
        codeVersion,
        schemaFingerprint,
        capabilityFingerprint,
        authGeneration,
        contextFingerprint);
  }

  private static PluginCatalogManifest initializedManifest() {
    return new PluginCatalogManifest(
        Map.of(
            "alpha.plugin",
            new PluginCatalogManifest.Entry(
                1, "1.0", FINGERPRINT, FINGERPRINT, GENERATION, CONTEXT)),
        FINGERPRINT);
  }
}
