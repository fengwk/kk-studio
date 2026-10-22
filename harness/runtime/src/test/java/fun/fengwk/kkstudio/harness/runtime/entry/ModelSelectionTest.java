package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** ModelSelection 的全局名称、可选 variant、canonical 和长度边界。 */
class ModelSelectionTest {

  @Test
  void keepsCanonicalReferences() {
    ModelSelection selection = ModelSelection.model("claude-sonnet", "default");
    assertEquals("claude-sonnet", selection.modelName());
    assertEquals("default", selection.variant());

    ModelSelection group = ModelSelection.group("fallback-group");
    assertEquals("fallback-group", group.modelName());
    assertNull(group.variant());
  }

  @Test
  void rejectsInvalidReferences() {
    assertThrows(NullPointerException.class, () -> new ModelSelection(null, "variant"));
    assertThrows(IllegalArgumentException.class, () -> new ModelSelection(" ", "variant"));
    assertThrows(IllegalArgumentException.class, () -> new ModelSelection(" model", "variant"));
    assertThrows(IllegalArgumentException.class, () -> new ModelSelection("model", "variant "));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelSelection("p".repeat(129), "variant"));
  }
}
