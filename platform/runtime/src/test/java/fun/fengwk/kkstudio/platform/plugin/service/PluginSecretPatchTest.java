package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.service.PluginSecretPatch.Operation;

import java.nio.charset.StandardCharsets;

/** 秘密补丁的三态契约：非法值一律拒绝，且任何输出都不回显秘密值。 */
class PluginSecretPatchTest {

  private static final String SECRET = "super-secret-value-98765";

  @Test
  void acceptsTheThreeRealOperations() {
    assertEquals(Operation.KEEP, PluginSecretPatch.keep().operation());
    assertEquals(Operation.CLEAR, PluginSecretPatch.clear().operation());
    assertEquals(Operation.SET, PluginSecretPatch.set(SECRET).operation());
    assertEquals(SECRET, PluginSecretPatch.set(SECRET).value());
  }

  @Test
  void keepAndClearMustNotCarryAValue() {
    assertThrows(
        IllegalArgumentException.class, () -> new PluginSecretPatch(Operation.KEEP, SECRET));
    assertThrows(
        IllegalArgumentException.class, () -> new PluginSecretPatch(Operation.CLEAR, SECRET));
  }

  @Test
  void setRejectsBlankPaddedOversizedAndMaskValues() {
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set(null));
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set("   "));
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set(" padded"));
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set("padded "));
    assertThrows(
        IllegalArgumentException.class,
        () -> PluginSecretPatch.set("x".repeat(PluginSecretPatch.MAX_SECRET_CHARS + 1)));
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set("***"));
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set("•••"));
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set("- - -"));

    // 多字节字符同样按 UTF-8 字节预算拒绝。
    String multiByte = "汉".repeat(PluginSecretPatch.MAX_SECRET_CHARS / 3 + 1);
    assertTrue(
        multiByte.getBytes(StandardCharsets.UTF_8).length > PluginSecretPatch.MAX_SECRET_CHARS);
    assertThrows(IllegalArgumentException.class, () -> PluginSecretPatch.set(multiByte));
  }

  @Test
  void toStringAndAccessorsNeverExposeTheValue() {
    PluginSecretPatch patch = PluginSecretPatch.set(SECRET);

    assertFalse(patch.toString().contains(SECRET));
    assertTrue(patch.toString().contains("<redacted>"));
    assertTrue(patch.toString().contains(Operation.SET.name()));
    assertFalse(patch.equals(null));
    assertFalse(patch.equals("not-a-patch"));
    assertEquals(patch, PluginSecretPatch.set(SECRET));
    assertThrows(NullPointerException.class, () -> new PluginSecretPatch(null, null));
  }
}
