package fun.fengwk.kkstudio.harness.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class PluginCatalogTest {

  private static final PluginDescriptor FIRST = descriptor("first", "1");
  private static final PluginDescriptor SECOND = descriptor("second", "2");
  private static final AgentToolId FIRST_TOOL_ID = new AgentToolId("test.first-tool");
  private static final AgentToolId SECOND_TOOL_ID = new AgentToolId("test.second-tool");

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
              registrar.registerTool(
                  "goal-tool", FIRST_TOOL_ID, pluginTool, ToolVisibility.SELECTABLE);
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
    assertEquals(
        new AgentToolDefinition(
            FIRST_TOOL_ID,
            pluginTool.descriptor(),
            ToolVisibility.SELECTABLE,
            AgentToolBackend.PLUGIN),
        tool.definition());
    assertEquals(
        new CustomEntryTypeContribution(
            new ContributionId(new PluginId("first"), "goal-type"), "goal", 0),
        catalog.customEntryTypes().get(0));
    assertEquals(
        new ContextProjectorContribution(
            new ContributionId(new PluginId("second"), "goal-projection"), projector, 0),
        catalog.contextProjectors().get(0));
  }

  @Test
  void sortsDescriptorsAndContributionsByRequiresThenPriorityThenIdentity() {
    PluginDescriptor base = new PluginDescriptor(new PluginId("base"), "base", "1", Set.of());
    PluginDescriptor peer = new PluginDescriptor(new PluginId("peer"), "peer", "1", Set.of());
    PluginDescriptor dependent =
        new PluginDescriptor(
            new PluginId("dependent"), "dependent", "1", Set.of(new PluginId("base")));
    HarnessPlugin basePlugin =
        HarnessPlugin.of(
            base, registrar -> registrar.registerCustomEntryType("z", "base.type", -100));
    HarnessPlugin peerPlugin =
        HarnessPlugin.of(
            peer, registrar -> registrar.registerCustomEntryType("a", "peer.type", 100));
    HarnessPlugin dependentPlugin =
        HarnessPlugin.of(
            dependent, registrar -> registrar.registerCustomEntryType("a", "dependent.type", 1000));

    PluginCatalog catalog = PluginCatalog.from(List.of(dependentPlugin, peerPlugin, basePlugin));

    assertEquals(List.of(base, dependent, peer), catalog.descriptors());
    assertEquals(
        List.of("peer", "base", "dependent"),
        catalog.customEntryTypes().stream()
            .map(contribution -> contribution.id().pluginId().value())
            .toList());
  }

  @Test
  void rejectsDependencyCycles() {
    HarnessPlugin first =
        HarnessPlugin.of(
            new PluginDescriptor(
                new PluginId("first"), "first", "1", Set.of(new PluginId("second"))),
            registrar -> {});
    HarnessPlugin second =
        HarnessPlugin.of(
            new PluginDescriptor(
                new PluginId("second"), "second", "1", Set.of(new PluginId("first"))),
            registrar -> {});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> PluginCatalog.from(List.of(first, second)));
    assertTrue(error.getMessage().contains("requires graph contains a cycle"));
  }

  @Test
  void validatesGraphBeforeCallingAnyContributor() {
    AtomicInteger contributions = new AtomicInteger();
    HarnessPlugin first =
        HarnessPlugin.of(
            new PluginDescriptor(
                new PluginId("first"), "first", "1", Set.of(new PluginId("second"))),
            registrar -> contributions.incrementAndGet());
    HarnessPlugin second =
        HarnessPlugin.of(
            new PluginDescriptor(
                new PluginId("second"), "second", "1", Set.of(new PluginId("first"))),
            registrar -> contributions.incrementAndGet());

    assertThrows(IllegalArgumentException.class, () -> PluginCatalog.from(List.of(first, second)));
    assertEquals(0, contributions.get());
  }

  @Test
  void readsEachDescriptorOnceAndContributesInTopologicalPluginIdOrder() {
    AtomicInteger descriptorReads = new AtomicInteger();
    List<String> contributions = new ArrayList<>();
    HarnessPlugin plugin =
        new HarnessPlugin() {
          @Override
          public PluginDescriptor descriptor() {
            descriptorReads.incrementAndGet();
            return FIRST;
          }

          @Override
          public void contribute(PluginRegistrar registrar) {
            contributions.add("first");
          }
        };
    PluginCatalog.from(List.of(plugin));
    assertEquals(1, descriptorReads.get());
    assertEquals(List.of("first"), contributions);

    PluginDescriptor base = descriptor("base", "1");
    PluginDescriptor middle =
        new PluginDescriptor(new PluginId("middle"), "middle", "1", Set.of(new PluginId("base")));
    PluginDescriptor top =
        new PluginDescriptor(new PluginId("top"), "top", "1", Set.of(new PluginId("middle")));
    List<String> orderedContributions = new ArrayList<>();
    HarnessPlugin basePlugin =
        HarnessPlugin.of(base, registrar -> orderedContributions.add("base"));
    HarnessPlugin middlePlugin =
        HarnessPlugin.of(middle, registrar -> orderedContributions.add("middle"));
    HarnessPlugin topPlugin = HarnessPlugin.of(top, registrar -> orderedContributions.add("top"));

    PluginCatalog.from(List.of(topPlugin, basePlugin, middlePlugin));
    assertEquals(List.of("base", "middle", "top"), orderedContributions);
  }

  @Test
  void usesTransitiveRequirementsWhenOrderingContributionsThroughEmptyPlugin() {
    PluginDescriptor base = descriptor("base", "1");
    PluginDescriptor middle =
        new PluginDescriptor(new PluginId("middle"), "middle", "1", Set.of(new PluginId("base")));
    PluginDescriptor top =
        new PluginDescriptor(new PluginId("top"), "top", "1", Set.of(new PluginId("middle")));
    HarnessPlugin basePlugin =
        HarnessPlugin.of(base, registrar -> registrar.registerCustomEntryType("base", "base.type"));
    HarnessPlugin middlePlugin = HarnessPlugin.of(middle, registrar -> {});
    HarnessPlugin topPlugin =
        HarnessPlugin.of(
            top, registrar -> registrar.registerCustomEntryType("top", "top.type", 100));

    PluginCatalog catalog = PluginCatalog.from(List.of(topPlugin, middlePlugin, basePlugin));

    assertEquals(
        Set.of(new PluginId("base"), new PluginId("middle")),
        catalog.transitiveRequires(new PluginId("top")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> catalog.transitiveRequires(new PluginId("top")).clear());
    assertEquals(
        List.of("base", "top"),
        catalog.customEntryTypes().stream()
            .map(contribution -> contribution.id().pluginId().value())
            .toList());
  }

  @Test
  void rejectsMissingRequiredPlugin() {
    HarnessPlugin plugin =
        HarnessPlugin.of(
            new PluginDescriptor(
                new PluginId("dependent"), "dependent", "1", Set.of(new PluginId("missing"))),
            registrar -> {});

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> PluginCatalog.from(List.of(plugin)));
    assertTrue(error.getMessage().contains("requires missing plugin"));
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
                                registrar.registerTool(
                                    "a", FIRST_TOOL_ID, firstTool, ToolVisibility.SELECTABLE)),
                        HarnessPlugin.of(
                            SECOND,
                            registrar ->
                                registrar.registerTool(
                                    "b", SECOND_TOOL_ID, secondTool, ToolVisibility.INTERNAL)))));
    assertTrue(error.getMessage().contains("duplicate tool name"));
    assertTrue(error.getMessage().contains("goal"));
  }

  /** 验证不同 model-visible name 也不能绕过跨插件 AgentToolId 全局唯一约束。 */
  @Test
  void rejectsDuplicateAgentToolIdsAcrossPlugins() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginCatalog.from(
                    List.of(
                        HarnessPlugin.of(
                            FIRST,
                            registrar ->
                                registrar.registerTool(
                                    "first",
                                    FIRST_TOOL_ID,
                                    pluginTool(toolDescriptor("first", "1")),
                                    ToolVisibility.SELECTABLE)),
                        HarnessPlugin.of(
                            SECOND,
                            registrar ->
                                registrar.registerTool(
                                    "second",
                                    FIRST_TOOL_ID,
                                    pluginTool(toolDescriptor("second", "1")),
                                    ToolVisibility.SELECTABLE)))));
    assertTrue(error.getMessage().contains("duplicate AgentToolId"));
    assertTrue(error.getMessage().contains(FIRST_TOOL_ID.toString()));
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
                      registrar.registerTool(
                          "writer", FIRST_TOOL_ID, writer, ToolVisibility.SELECTABLE);
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
                                    "reader",
                                    FIRST_TOOL_ID,
                                    undeclaredOwner,
                                    ToolVisibility.SELECTABLE)))));
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
                                    "duplicate",
                                    FIRST_TOOL_ID,
                                    duplicateAccess,
                                    ToolVisibility.SELECTABLE)))));
    assertTrue(duplicate.getMessage().contains("duplicate state access"));
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
        new CustomEntryTypeContribution(new ContributionId(new PluginId("first"), "a"), "state", 0),
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
                            registrar.registerTool(
                                "a", FIRST_TOOL_ID, null, ToolVisibility.SELECTABLE)))));
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
                                FIRST_TOOL_ID,
                                pluginTool(toolDescriptor("goal", "1")),
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
    return new PluginDescriptor(new PluginId(id), "plugin " + id, version, Set.of());
  }

  private static ToolDescriptor toolDescriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
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
