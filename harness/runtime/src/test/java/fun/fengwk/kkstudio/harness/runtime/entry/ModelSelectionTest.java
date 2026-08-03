package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** ModelSelection 的非空、canonical name 和长度边界。 */
class ModelSelectionTest {

  @Test
  void keepsCanonicalReferences() {
    ModelSelection selection = new ModelSelection("anthropic", "claude-sonnet", "default");

    assertEquals("anthropic", selection.providerName());
    assertEquals("claude-sonnet", selection.modelName());
    assertEquals("default", selection.variant());
  }

  @Test
  void rejectsInvalidReferences() {
    assertThrows(NullPointerException.class, () -> new ModelSelection(null, "model", "variant"));
    assertThrows(IllegalArgumentException.class, () -> new ModelSelection(" ", "model", "variant"));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelSelection(" provider", "model", "variant"));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelSelection("provider", " model", "variant"));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelSelection("provider", "model", "variant "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelSelection("p".repeat(129), "model", "variant"));
  }
}
