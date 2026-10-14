package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** {@link PluginCatalogRecord} 的 epoch 与 manifest 一致性门禁测试。 */
class PluginCatalogRecordTest {

  private static final String FINGERPRINT = "a".repeat(64);

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
                new PluginCatalogManifest.Entry(4, "1.0", FINGERPRINT, FINGERPRINT)),
            FINGERPRINT);

    assertEquals(4, new PluginCatalogRecord(4, manifest).epoch());
    assertThrows(IllegalArgumentException.class, () -> new PluginCatalogRecord(3, manifest));
  }

  private static PluginCatalogManifest initializedManifest() {
    return new PluginCatalogManifest(
        Map.of("alpha.plugin", new PluginCatalogManifest.Entry(1, "1.0", FINGERPRINT, FINGERPRINT)),
        FINGERPRINT);
  }
}
