package fun.fengwk.kkstudio.harness.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
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
    PluginTool pluginTool = pluginTool(toolDescriptor("goal", "1"));
    ContextProjector projector = view -> List.of();
    HarnessPlugin first =
        HarnessPlugin.of(
            FIRST,
            registrar -> {
              registrar.registerTool("goal-tool", pluginTool, ToolVisibility.SELECTABLE);
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
    assertSame(pluginTool, tool.tool());
    assertEquals(pluginTool.descriptor(), tool.descriptor());
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
  void rejectsDuplicateToolNameAcrossVersionsAndPlugins() {
    PluginTool firstTool = pluginTool(toolDescriptor("goal", "1"));
    PluginTool secondTool = pluginTool(toolDescriptor("goal", "2"));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar ->
                                registrar.registerTool("a", firstTool, ToolVisibility.SELECTABLE)),
                        HarnessPlugin.of(
                            SECOND,
                            registrar ->
                                registrar.registerTool(
                                    "b", secondTool, ToolVisibility.INTERNAL)))));
    assertTrue(error.getMessage().contains("duplicate tool name"));
    assertTrue(error.getMessage().contains("goal"));
  }

  @Test
  void freezesStateDeclarationsAndRejectsInvalidToolDeclarations() {
    PluginTool writer =
        pluginTool(
            toolDescriptor("goal", "1"),
            List.of(new PluginStateDeclaration("state", PluginStateMode.WRITE)));
    PluginCatalog catalog =
        PluginCatalog.from(
            List.of(
                HarnessPlugin.of(
                    FIRST,
                    registrar -> {
                      registrar.registerCustomEntryType("state-type", "state");
                      registrar.registerTool("writer", writer, ToolVisibility.SELECTABLE);
                    })));
    assertEquals(
        List.of(new PluginStateDeclaration("state", PluginStateMode.WRITE)),
        catalog.findTool("goal").orElseThrow().stateAccesses());
    assertEquals(Optional.empty(), catalog.findTool("missing"));

    PluginTool undeclaredOwner =
        pluginTool(
            toolDescriptor("other", "1"),
            List.of(new PluginStateDeclaration("missing", PluginStateMode.READ)));
    IllegalArgumentException unregistered =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar ->
                                registrar.registerTool(
                                    "reader", undeclaredOwner, ToolVisibility.SELECTABLE)))));
    assertTrue(unregistered.getMessage().contains("unregistered custom entry type"));

    PluginTool duplicateAccess =
        pluginTool(
            toolDescriptor("duplicate", "1"),
            List.of(
                new PluginStateDeclaration("state", PluginStateMode.READ),
                new PluginStateDeclaration("state", PluginStateMode.WRITE)));
    IllegalArgumentException duplicate =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar ->
                                registrar.registerTool(
                                    "duplicate", duplicateAccess, ToolVisibility.SELECTABLE)))));
    assertTrue(duplicate.getMessage().contains("duplicate state access"));

    PluginTool environment =
        pluginTool(
            new ToolDescriptor(
                "environment",
                "1",
                ToolType.ENVIRONMENT,
                "environment",
                "environment",
                new ToolParamsSchema("", Map.of(), Set.of(), false),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(5)));
    IllegalArgumentException nonPlatform =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar ->
                                registrar.registerTool(
                                    "environment", environment, ToolVisibility.SELECTABLE)))));
    assertTrue(nonPlatform.getMessage().contains("must be PLATFORM"));
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
  void rejectsNonCanonicalIdsAndNullTool() {
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
                                "a", pluginTool(toolDescriptor("goal", "1")), null)))));
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

  private static PluginTool pluginTool(ToolDescriptor descriptor) {
    return pluginTool(descriptor, List.of());
  }

  private static PluginTool pluginTool(
      ToolDescriptor descriptor, List<PluginStateDeclaration> stateAccesses) {
    return new PluginTool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public List<PluginStateDeclaration> stateAccesses() {
        return stateAccesses;
      }

      @Override
      public PluginToolResult execute(PluginToolContext context, ToolCall call) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
