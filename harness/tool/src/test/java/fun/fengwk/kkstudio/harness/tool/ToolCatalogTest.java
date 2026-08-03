package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ToolCatalogTest {

  @Test
  void combinesSelectablePlatformToolsWithFixedEnvironmentTools() {
    ToolDescriptor platform = descriptor("platform_only");

    ToolDescriptor loadSkill = descriptor("load_skill");
    ToolCatalog catalog = new ToolCatalog(List.of(platform, loadSkill), Set.of(loadSkill.name()));

    assertEquals(platform, catalog.require("platform_only"));
    assertEquals(EnvironmentToolCatalog.descriptors().size() + 1, catalog.descriptors().size());
    assertTrue(catalog.find("load_skill").isEmpty());
    assertEquals(loadSkill, catalog.findInternal("load_skill").orElseThrow());
  }

  @Test
  void rejectsDuplicateSelectableNames() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCatalog(
                List.of(descriptor("platform_only"), descriptor("platform_only")), Set.of()));
  }

  @Test
  void rejectsUnknownInternalPlatformToolNames() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCatalog(List.of(descriptor("platform_only")), Set.of("load_skill")));
  }

  @Test
  void rejectsPlatformEnvironmentNameCollision() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCatalog(
                List.of(descriptor(EnvironmentToolCatalog.descriptors().getFirst().name())),
                Set.of()));
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "1",
        ToolType.PLATFORM,
        name + " description",
        name,
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }
}
