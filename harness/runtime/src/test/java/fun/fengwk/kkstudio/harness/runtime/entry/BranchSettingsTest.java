package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Branch settings 的 immutable snapshot、名称边界和 active tool 规范化。 */
class BranchSettingsTest {

  @Test
  void preservesEnvironmentAndDeduplicatesToolsInOrder() {
    List<String> sourceTools = new ArrayList<>(List.of("read", "grep", "read", "bash"));
    BranchSettings settings =
        new BranchSettings(
            "workspace-A",
            "coding",
            new ModelSelection("anthropic", "claude-sonnet", "default"),
            "high",
            sourceTools);

    sourceTools.add("write");
    assertEquals("workspace-A", settings.environmentName());
    assertEquals(List.of("read", "grep", "bash"), settings.activeTools());
    assertThrows(UnsupportedOperationException.class, () -> settings.activeTools().add("write"));
  }

  @Test
  void allowsNoEnvironmentButRequiresCanonicalNames() {
    BranchSettings settings =
        new BranchSettings(
            null,
            "coding",
            new ModelSelection("anthropic", "claude-sonnet", "default"),
            "high",
            List.of());

    assertEquals(null, settings.environmentName());
    assertThrows(
        IllegalArgumentException.class,
        () -> new BranchSettings(" workspace-A", "coding", settings.model(), "high", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BranchSettings(null, "coding", settings.model(), " high", List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new BranchSettings(null, "coding", settings.model(), "high", List.of("read", null)));
  }

  @Test
  void rejectsNullCollectionsBlankToolsAndOversizedNames() {
    ModelSelection model = new ModelSelection("anthropic", "claude-sonnet", "default");
    assertThrows(
        NullPointerException.class, () -> new BranchSettings(null, "coding", model, "high", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BranchSettings(null, "coding", model, "high", List.of(" ")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BranchSettings(null, "coding", model, "high", List.of("t".repeat(129))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BranchSettings("e".repeat(129), "coding", model, "high", List.of()));
  }
}
