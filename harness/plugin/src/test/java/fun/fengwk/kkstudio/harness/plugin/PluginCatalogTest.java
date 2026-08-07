package fun.fengwk.kkstudio.harness.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class PluginCatalogTest {

  private static final PluginDescriptor FIRST = descriptor("first", "1");
  private static final PluginDescriptor SECOND = descriptor("second", "2");

  @Test
  void permitsEmptyPluginList() {
    PluginCatalog catalog = PluginCatalog.from(List.of());
    assertTrue(catalog.descriptors().isEmpty());
    assertTrue(catalog.tools().isEmpty());
    assertTrue(catalog.customEntryTypes().isEmpty());
    assertTrue(catalog.contextProjectors().isEmpty());
  }

  @Test
  void collectsContributionsWithScopedIdsInRegistrationOrder() {
    ToolFactory factory = ToolFactory.singleton(tool(toolDescriptor("goal", "1")));
    ContextProjector projector = view -> List.of();
    HarnessPlugin first =
        HarnessPlugin.of(
            FIRST,
            registrar -> {
              registrar.registerTool("goal-tool", factory, ToolVisibility.SELECTABLE);
              registrar.registerCustomEntryType("goal-type", "goal");
            });
    HarnessPlugin second =
        HarnessPlugin.of(
            SECOND, registrar -> registrar.registerContextProjector("goal-projection", projector));

    PluginCatalog catalog = PluginCatalog.from(List.of(first, second));

    assertEquals(List.of(FIRST, SECOND), catalog.descriptors());
    assertEquals(1, catalog.tools().size());
    ToolContribution tool = catalog.tools().get(0);
    assertEquals(new ContributionId(new PluginId("first"), "goal-tool"), tool.id());
    assertSame(factory, tool.factory());
    assertEquals(ToolVisibility.SELECTABLE, tool.visibility());
    assertEquals(
        new CustomEntryTypeContribution(
            new ContributionId(new PluginId("first"), "goal-type"), "goal"),
        catalog.customEntryTypes().get(0));
    assertEquals(
        new ContextProjectorContribution(
            new ContributionId(new PluginId("second"), "goal-projection"), projector),
        catalog.contextProjectors().get(0));
  }

  @Test
  void allowsSameLocalNameInDifferentPlugins() {
    HarnessPlugin first =
        HarnessPlugin.of(FIRST, registrar -> registrar.registerCustomEntryType("state", "goal"));
    HarnessPlugin second =
        HarnessPlugin.of(SECOND, registrar -> registrar.registerCustomEntryType("state", "memory"));
    PluginCatalog catalog = PluginCatalog.from(List.of(first, second));
    assertEquals(2, catalog.customEntryTypes().size());
    assertEquals("first", catalog.customEntryTypes().get(0).id().pluginId().value());
    assertEquals("second", catalog.customEntryTypes().get(1).id().pluginId().value());
  }

  @Test
  void rejectsDuplicatePluginIds() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(FIRST, registrar -> {}),
                        HarnessPlugin.of(FIRST, registrar -> {}))));
    assertTrue(error.getMessage().contains("duplicate plugin id"));
  }

  @Test
  void rejectsDuplicateLocalNameWithinSamePluginAcrossContributionTypes() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar -> {
                              registrar.registerCustomEntryType("state", "goal");
                              registrar.registerContextProjector("state", view -> List.of());
                            }))));
    assertTrue(error.getMessage().contains("duplicate contribution local name"));
    assertTrue(error.getMessage().contains("state"));
  }

  @Test
  void rejectsDuplicateToolNameVersionAcrossPlugins() {
    ToolFactory factory = ToolFactory.singleton(tool(toolDescriptor("goal", "1")));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar ->
                                registrar.registerTool("a", factory, ToolVisibility.SELECTABLE)),
                        HarnessPlugin.of(
                            SECOND,
                            registrar ->
                                registrar.registerTool("b", factory, ToolVisibility.INTERNAL)))));
    assertTrue(error.getMessage().contains("duplicate tool (name, version)"));
    assertTrue(error.getMessage().contains("goal@1"));
  }

  @Test
  void allowsSameCustomTypeOwnershipInDifferentPlugins() {
    HarnessPlugin first =
        HarnessPlugin.of(FIRST, registrar -> registrar.registerCustomEntryType("a", "state"));
    HarnessPlugin second =
        HarnessPlugin.of(SECOND, registrar -> registrar.registerCustomEntryType("b", "state"));
    PluginCatalog catalog = PluginCatalog.from(List.of(first, second));
    assertEquals(2, catalog.customEntryTypes().size());
  }

  @Test
  void rejectsDuplicateCustomEntryTypeOwnershipWithinSamePlugin() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar -> {
                              registrar.registerCustomEntryType("a", "goal");
                              registrar.registerCustomEntryType("b", "goal");
                            }))));
    assertTrue(error.getMessage().contains("duplicate custom entry type"));
  }

  @Test
  void findsCustomEntryTypeOwnershipByStructuredKey() {
    HarnessPlugin first =
        HarnessPlugin.of(FIRST, registrar -> registrar.registerCustomEntryType("a", "state"));
    HarnessPlugin second =
        HarnessPlugin.of(SECOND, registrar -> registrar.registerCustomEntryType("b", "state"));
    PluginCatalog catalog = PluginCatalog.from(List.of(first, second));

    Optional<CustomEntryTypeContribution> found =
        catalog.findCustomEntryType(new PluginId("first"), "state");
    assertEquals(
        new CustomEntryTypeContribution(new ContributionId(new PluginId("first"), "a"), "state"),
        found.orElseThrow());
    assertEquals(
        "second",
        catalog
            .findCustomEntryType(new PluginId("second"), "state")
            .orElseThrow()
            .id()
            .pluginId()
            .value());
    assertEquals(Optional.empty(), catalog.findCustomEntryType(new PluginId("first"), "memory"));
    assertEquals(Optional.empty(), catalog.findCustomEntryType(new PluginId("missing"), "state"));
    assertThrows(NullPointerException.class, () -> catalog.findCustomEntryType(null, "state"));
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.findCustomEntryType(new PluginId("first"), "State"));
  }

  @Test
  void rejectsNonCanonicalIdsAndNullFactory() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PluginCatalog.from(
                List.of(
                    HarnessPlugin.of(
                        FIRST, registrar -> registrar.registerCustomEntryType("Bad-Id", "goal")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PluginCatalog.from(
                List.of(
                    HarnessPlugin.of(
                        FIRST, registrar -> registrar.registerCustomEntryType("a", "Goal-Type")))));
    assertThrows(
        NullPointerException.class,
        () ->
            PluginCatalog.from(
                List.of(
                    HarnessPlugin.of(
                        FIRST,
                        registrar ->
                            registrar.registerTool("a", null, ToolVisibility.SELECTABLE)))));
    assertThrows(
        NullPointerException.class,
        () ->
            PluginCatalog.from(
                List.of(
                    HarnessPlugin.of(
                        FIRST,
                        registrar ->
                            registrar.registerTool(
                                "a",
                                ToolFactory.singleton(tool(toolDescriptor("goal", "1"))),
                                null)))));
  }

  @Test
  void exposesImmutableLists() {
    PluginCatalog catalog =
        PluginCatalog.from(
            List.of(
                HarnessPlugin.of(
                    FIRST, registrar -> registrar.registerCustomEntryType("a", "goal"))));
    assertThrows(UnsupportedOperationException.class, () -> catalog.descriptors().clear());
    assertThrows(UnsupportedOperationException.class, () -> catalog.tools().clear());
    assertThrows(UnsupportedOperationException.class, () -> catalog.customEntryTypes().clear());
    assertThrows(UnsupportedOperationException.class, () -> catalog.contextProjectors().clear());
  }

  private static PluginDescriptor descriptor(String id, String version) {
    return new PluginDescriptor(new PluginId(id), "plugin " + id, version);
  }

  private static ToolDescriptor toolDescriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
        ToolType.PLATFORM,
        name + " tool",
        name,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static Tool tool(ToolDescriptor descriptor) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
