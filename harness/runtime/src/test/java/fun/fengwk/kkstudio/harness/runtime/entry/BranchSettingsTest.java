package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Branch settings 的 immutable snapshot、canonical workspace path 规范化与 agentName 校验。 */
class BranchSettingsTest {

  @Test
  void preservesWorkspacePathAndModel() {
    BranchSettings settings =
        new BranchSettings(
            "projects/web", "coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    assertEquals("projects/web", settings.workspacePath());
    assertEquals("coding", settings.agentName());
  }

  @Test
  void allowsNoWorkspaceAndValidatesCanonicalPathShape() {
    BranchSettings settings =
        new BranchSettings(
            null, "coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    assertNull(settings.workspacePath());
    // absolute 路径在构造边界拒绝（canonical 相对 wire 路径契约）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BranchSettings(
                "/projects/web",
                "coding",
                new ModelSelection("anthropic", "claude-sonnet", "default")));
    // '..' 段拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BranchSettings(
                "../escape",
                "coding",
                new ModelSelection("anthropic", "claude-sonnet", "default")));
  }

  @Test
  void replacesWorkspacePathViaWithMethod() {
    BranchSettings base =
        new BranchSettings(
            "projects/web", "coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    BranchSettings cleared = base.withWorkspacePath(null);
    BranchSettings rebound = cleared.withWorkspacePath("other/repo");

    assertNull(cleared.workspacePath());
    assertEquals("other/repo", rebound.workspacePath());
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
