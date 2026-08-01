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
  void combinesLocalSelectableToolsWithFixedEnvironmentTools() {
    ToolDescriptor local = descriptor("local_only");

    ToolDescriptor loadSkill = descriptor("load_skill");
    ToolCatalog catalog = new ToolCatalog(List.of(local, loadSkill), Set.of(loadSkill.name()));

    assertEquals(local, catalog.require("local_only"));
    assertEquals(EnvironmentToolCatalog.descriptors().size() + 1, catalog.descriptors().size());
    assertTrue(catalog.find("load_skill").isEmpty());
    assertEquals(loadSkill, catalog.findRuntimeManaged("load_skill").orElseThrow());
  }

  @Test
  void rejectsDuplicateSelectableNames() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCatalog(
                List.of(descriptor("local_only"), descriptor("local_only")), Set.of("load_skill")));
  }

  @Test
  void rejectsDuplicateRuntimeManagedNames() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCatalog(
                List.of(descriptor("load_skill"), descriptor("load_skill")), Set.of("load_skill")));
  }

  @Test
  void rejectsLocalEnvironmentNameCollision() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCatalog(
                List.of(descriptor(EnvironmentToolCatalog.descriptors().getFirst().name())),
                Set.of("load_skill")));
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "1",
        name + " description",
        name,
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }
}
