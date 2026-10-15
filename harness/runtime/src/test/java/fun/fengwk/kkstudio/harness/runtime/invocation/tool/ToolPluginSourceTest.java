package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** {@link ToolPluginSource} 身份与指纹不变式契约测试。 */
class ToolPluginSourceTest {

  private static final String VALID_ID = "com.example.search-tool";
  private static final String VALID_VERSION = "1.2.3";
  private static final long VALID_REVISION = 42L;
  private static final String VALID_FP1 =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String VALID_FP2 =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
  private static final UUID VALID_AUTH = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  void acceptsValidPluginSourceValues() {
    ToolPluginSource source =
        new ToolPluginSource(
            VALID_ID, VALID_VERSION, VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH);

    assertEquals(VALID_ID, source.pluginId());
    assertEquals(VALID_VERSION, source.pluginVersion());
    assertEquals(VALID_REVISION, source.configRevision());
    assertEquals(VALID_FP1, source.capabilityFingerprint());
    assertEquals(VALID_FP2, source.contextFingerprint());
    assertEquals(VALID_AUTH, source.authGeneration());
  }

  @Test
  void validatesFingerprintFormat() {
    assertTrue(ToolPluginSource.isFingerprint(VALID_FP1));
    assertTrue(ToolPluginSource.isFingerprint(VALID_FP2));
    assertFalse(ToolPluginSource.isFingerprint(null));
    assertFalse(ToolPluginSource.isFingerprint(""));
    assertFalse(ToolPluginSource.isFingerprint("abc"));
    // 63 字符
    assertFalse(ToolPluginSource.isFingerprint(VALID_FP1.substring(1)));
    // 65 字符
    assertFalse(ToolPluginSource.isFingerprint(VALID_FP1 + "a"));
    // 大写 hex
    assertFalse(ToolPluginSource.isFingerprint(VALID_FP1.toUpperCase()));
    // 非 hex 字符
    assertFalse(ToolPluginSource.isFingerprint(VALID_FP1.substring(0, 63) + "g"));
  }

  @Test
  void rejectsInvalidPluginId() {
    // null / blank / 大写 / 包含非法字符 / 超长
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                null, VALID_VERSION, VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                "", VALID_VERSION, VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                "MyPlugin", VALID_VERSION, VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                "my_plugin", VALID_VERSION, VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                "a".repeat(65), VALID_VERSION, VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
  }

  @Test
  void rejectsInvalidPluginVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(VALID_ID, null, VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolPluginSource(VALID_ID, "", VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                VALID_ID, "   ", VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                VALID_ID, " 1.0.0", VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                VALID_ID, "1.0.0 ", VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                VALID_ID, "v".repeat(65), VALID_REVISION, VALID_FP1, VALID_FP2, VALID_AUTH));
  }

  @Test
  void rejectsNonPositiveConfigRevision() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolPluginSource(VALID_ID, VALID_VERSION, 0L, VALID_FP1, VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolPluginSource(VALID_ID, VALID_VERSION, -1L, VALID_FP1, VALID_FP2, VALID_AUTH));
  }

  @Test
  void rejectsInvalidFingerprintsAndNullAuth() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                VALID_ID, VALID_VERSION, VALID_REVISION, "invalid", VALID_FP2, VALID_AUTH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolPluginSource(
                VALID_ID, VALID_VERSION, VALID_REVISION, VALID_FP1, "invalid", VALID_AUTH));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolPluginSource(
                VALID_ID, VALID_VERSION, VALID_REVISION, VALID_FP1, VALID_FP2, null));
  }
}
