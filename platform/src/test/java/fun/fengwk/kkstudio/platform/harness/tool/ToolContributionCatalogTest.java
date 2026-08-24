package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.plugin.api.ContributionId;
import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginRegistrar;
import fun.fengwk.kkstudio.harness.plugin.api.PluginStateDeclaration;
import fun.fengwk.kkstudio.harness.plugin.api.PluginTool;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolResult;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

class ToolContributionCatalogTest {

  @Test
  void mergesLocalAndPluginToolsWithDependencyAndPriorityOrder() {
    PluginDescriptor base = descriptor("base", Set.of());
    PluginDescriptor dependent = descriptor("dependent", Set.of(new PluginId("base")));
    HarnessPlugin basePlugin =
        plugin(
            base,
            registrar ->
                registrar.registerTool(
                    "base",
                    pluginTool(descriptor("plugin_base", "1")),
                    ToolVisibility.SELECTABLE,
                    0));
    HarnessPlugin dependentPlugin =
        plugin(
            dependent,
            registrar ->
                registrar.registerTool(
                    "dependent",
                    pluginTool(descriptor("plugin_dependent", "1")),
                    ToolVisibility.INTERNAL,
                    100));
    ToolFactory local =
        factory(
            descriptor("local", "1"),
            tool(descriptor("local", "1")),
            ToolVisibility.SELECTABLE,
            50);

    ToolContributionCatalog catalog =
        new ToolContributionCatalog(
            List.of(local), PluginCatalog.from(List.of(dependentPlugin, basePlugin)));

    assertEquals(
        List.of("core:local@1", "plugin:base:base", "plugin:dependent:dependent"),
        catalog.entries().stream().map(ToolContributionCatalog.Entry::identity).toList());
    assertEquals(
        new ContributionId(new PluginId("dependent"), "dependent"),
        catalog.find("plugin_dependent", "1").orElseThrow().contributionId());
    assertTrue(catalog.find(new ContributionId(new PluginId("base"), "base")).isPresent());
    assertTrue(catalog.find("missing").isEmpty());

    assertTrue(catalog.toToolCatalog().findSelectable("local").isPresent());
    assertTrue(catalog.toToolCatalog().findInternal("plugin_dependent").isPresent());
    assertTrue(catalog.toToolCatalog().findSelectable("plugin_dependent").isEmpty());
  }

  @Test
  void createsLocalToolAndRejectsDescriptorDriftOrPluginEntry() {
    ToolDescriptor descriptor = descriptor("local", "1");
    Tool expected = tool(descriptor);
    ToolContributionCatalog catalog =
        new ToolContributionCatalog(
            List.of(factory(descriptor, expected, ToolVisibility.SELECTABLE, 0)),
            PluginCatalog.from(List.of()));
    ToolContributionCatalog.Entry entry = catalog.find("local", "1").orElseThrow();
    assertSame(expected, catalog.createLocalTool(entry));

    ToolDescriptor drifted = descriptor("local", "1");
    ToolFactory driftingFactory =
        factory(descriptor, toolWithDifferentDescriptor(drifted), ToolVisibility.SELECTABLE, 0);
    ToolContributionCatalog driftingCatalog =
        new ToolContributionCatalog(List.of(driftingFactory), PluginCatalog.from(List.of()));
    IllegalArgumentException drift =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                driftingCatalog.createLocalTool(driftingCatalog.find("local", "1").orElseThrow()));
    assertTrue(drift.getMessage().contains("does not match frozen"));

    HarnessPlugin plugin =
        plugin(
            descriptor("plugin", Set.of()),
            registrar ->
                registrar.registerTool(
                    "tool", pluginTool(descriptor("plugin_tool", "1")), ToolVisibility.SELECTABLE));
    ToolContributionCatalog.Entry pluginEntry =
        new ToolContributionCatalog(List.of(), PluginCatalog.from(List.of(plugin)))
            .find("plugin_tool", "1")
            .orElseThrow();
    assertTrue(pluginEntry.isPlugin());
    assertTrue(!pluginEntry.isLocal());
    assertEquals("plugin:plugin:tool", pluginEntry.stableIdentity());
    assertEquals(new PluginId("plugin"), pluginEntry.pluginId());
    IllegalArgumentException notLocal =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ToolContributionCatalog(List.of(), PluginCatalog.from(List.of(plugin)))
                    .createLocalTool(pluginEntry));
    assertInstanceOf(IllegalArgumentException.class, notLocal);
  }

  @Test
  void rejectsInvalidLocalFactoriesAndDuplicateNames() {
    ToolDescriptor environment =
        new ToolDescriptor(
            "environment",
            "1",
            ToolType.ENVIRONMENT,
            "environment",
            "environment",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
    IllegalArgumentException nonPlatform =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ToolContributionCatalog(
                    List.of(factory(environment, tool(environment), ToolVisibility.SELECTABLE, 0)),
                    PluginCatalog.from(List.of())));
    assertTrue(nonPlatform.getMessage().contains("PLATFORM descriptor"));

    ToolDescriptor duplicate = descriptor("duplicate", "1");
    IllegalArgumentException duplicateError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ToolContributionCatalog(
                    List.of(
                        factory(duplicate, tool(duplicate), ToolVisibility.SELECTABLE, 0),
                        factory(duplicate, tool(duplicate), ToolVisibility.SELECTABLE, 0)),
                    PluginCatalog.from(List.of())));
    assertTrue(duplicateError.getMessage().contains("duplicate Platform tool name"));
  }

  @Test
  void exposesEntryProvenanceAndRejectsInvalidEntryShapes() {
    ToolDescriptor descriptor = descriptor("local", "1");
    ToolContributionCatalog catalog =
        new ToolContributionCatalog(
            List.of(factory(descriptor, tool(descriptor), ToolVisibility.SELECTABLE, 0)),
            PluginCatalog.from(List.of()));
    ToolContributionCatalog.Entry local = catalog.find("local", "1").orElseThrow();
    assertTrue(local.isLocal());
    assertTrue(!local.isPlugin());
    assertEquals("core:local@1", local.stableIdentity());
    assertTrue(local.contributionId() == null);
    assertTrue(local.pluginId() == null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolContributionCatalog.Entry(
                descriptor,
                ToolVisibility.SELECTABLE,
                0,
                " ",
                ToolContributionCatalog.Origin.LOCAL,
                factory(descriptor, tool(descriptor), ToolVisibility.SELECTABLE, 0),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolContributionCatalog.Entry(
                descriptor,
                ToolVisibility.SELECTABLE,
                0,
                "local",
                ToolContributionCatalog.Origin.LOCAL,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolContributionCatalog.Entry(
                descriptor,
                ToolVisibility.SELECTABLE,
                0,
                "plugin",
                ToolContributionCatalog.Origin.PLUGIN,
                null,
                null));
  }

  private static PluginDescriptor descriptor(String id, Set<PluginId> requires) {
    return new PluginDescriptor(new PluginId(id), id, "1", requires);
  }

  private static ToolDescriptor descriptor(String name, String version) {
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

  private static HarnessPlugin plugin(
      PluginDescriptor descriptor, Consumer<PluginRegistrar> contributor) {
    return HarnessPlugin.of(descriptor, contributor);
  }

  private static ToolFactory factory(
      ToolDescriptor descriptor, Tool tool, ToolVisibility visibility, int priority) {
    return new ToolFactory() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public Tool create() {
        return tool;
      }

      @Override
      public ToolVisibility visibility() {
        return visibility;
      }

      @Override
      public int priority() {
        return priority;
      }
    };
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

  private static Tool toolWithDifferentDescriptor(ToolDescriptor descriptor) {
    ToolDescriptor actual =
        new ToolDescriptor(
            descriptor.name(),
            descriptor.version(),
            descriptor.type(),
            "different description",
            descriptor.rendererKey(),
            descriptor.inputSchema(),
            descriptor.sideEffect(),
            descriptor.timeout());
    return tool(actual);
  }

  private static PluginTool pluginTool(ToolDescriptor descriptor) {
    return new PluginTool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public List<PluginStateDeclaration> stateAccesses() {
        return List.of();
      }

      @Override
      public PluginToolResult execute(PluginToolContext context, ToolCall call) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
