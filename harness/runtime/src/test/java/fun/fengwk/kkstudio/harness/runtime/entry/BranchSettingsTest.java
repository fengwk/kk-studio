package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

/** Branch settings 的 immutable snapshot、Environment route identity 和 canonical name 规范化。 */
class BranchSettingsTest {

  private static final EnvironmentBinding ENV =
      EnvironmentBindings.binding("123e4567-e89b-12d3-a456-426614174000");
  private static final EnvironmentBinding OTHER =
      EnvironmentBindings.binding("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

  @Test
  void preservesEnvironmentAndModel() {
    BranchSettings settings =
        new BranchSettings(
            ENV, "coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    assertEquals(ENV, settings.environment());
    assertEquals("coding", settings.agentName());
  }

  @Test
  void allowsNoEnvironmentButRequiresCanonicalNames() {
    BranchSettings settings =
        new BranchSettings(
            null, "coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    assertNull(settings.environment());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BranchSettings(
                EnvironmentBindings.binding("123E4567-E89B-12D3-A456-426614174000"),
                "coding",
                settings.model()));
  }

  @Test
  void replacesEnvironmentRouteIdentityViaWithMethod() {
    BranchSettings base =
        new BranchSettings(
            ENV, "coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    BranchSettings cleared = base.withEnvironment(null);
    BranchSettings rebound = cleared.withEnvironment(OTHER);

    assertNull(cleared.environment());
    assertEquals(OTHER, rebound.environment());
    assertEquals(base.agentName(), rebound.agentName());
    assertEquals(base.model(), rebound.model());
  }

  @Test
  void rejectsNullModelBlankAndOversizedNames() {
    ModelSelection model = new ModelSelection("anthropic", "claude-sonnet", "default");
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings(null, " ", model));
    assertThrows(
        IllegalArgumentException.class, () -> new BranchSettings(null, "c".repeat(129), model));
    assertThrows(NullPointerException.class, () -> new BranchSettings(null, "coding", null));
  }
}
