package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.util.ArrayList;
import java.util.List;

/** Branch settings 的 immutable snapshot、Environment route identity 和 active tool 规范化。 */
class BranchSettingsTest {

  private static final EnvironmentName ENV =
      new EnvironmentName("123e4567-e89b-12d3-a456-426614174000");
  private static final EnvironmentName OTHER =
      new EnvironmentName("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

  @Test
  void preservesEnvironmentAndDeduplicatesToolsInOrder() {
    List<String> sourceTools = new ArrayList<>(List.of("read", "grep", "read", "bash"));
    BranchSettings settings =
        new BranchSettings(
            ENV,
            "coding",
            new ModelSelection("anthropic", "claude-sonnet", "default"),
            "high",
            sourceTools);

    sourceTools.add("write");
    assertEquals(ENV, settings.environmentName());
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

    assertNull(settings.environmentName());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BranchSettings(
                new EnvironmentName("123E4567-E89B-12D3-A456-426614174000"),
                "coding",
                settings.model(),
                "high",
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BranchSettings(null, "coding", settings.model(), " high", List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new BranchSettings(null, "coding", settings.model(), "high", List.of("read", null)));
  }

  @Test
  void replacesEnvironmentRouteIdentityViaWithMethod() {
    BranchSettings base =
        new BranchSettings(
            ENV,
            "coding",
            new ModelSelection("anthropic", "claude-sonnet", "default"),
            "high",
            List.of("read"));

    BranchSettings cleared = base.withEnvironmentName(null);
    BranchSettings rebound = cleared.withEnvironmentName(OTHER);

    assertNull(cleared.environmentName());
    assertEquals(OTHER, rebound.environmentName());
    assertEquals(base.agentName(), rebound.agentName());
    assertEquals(base.model(), rebound.model());
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
        () -> new BranchSettings(null, "c".repeat(129), model, "high", List.of()));
  }
}
