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
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
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

class AgentToolRegistryTest {

  private static final AgentToolId LOCAL_TOOL_ID = new AgentToolId("test.local-tool");
  private static final AgentToolId BASE_TOOL_ID = new AgentToolId("test.base-tool");
  private static final AgentToolId DEPENDENT_TOOL_ID = new AgentToolId("test.dependent-tool");
  private static final AgentToolId TOP_TOOL_ID = new AgentToolId("test.top-tool");
  private static final AgentToolId PLUGIN_TOOL_ID = new AgentToolId("test.plugin-tool");
  private static final AgentToolId DUPLICATE_FIRST_ID = new AgentToolId("test.duplicate-first");
  private static final AgentToolId DUPLICATE_SECOND_ID = new AgentToolId("test.duplicate-second");

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
                    BASE_TOOL_ID,
                    pluginTool(descriptor("plugin_base", "1")),
                    ToolVisibility.SELECTABLE,
                    0));
    HarnessPlugin dependentPlugin =
        plugin(
            dependent,
            registrar ->
                registrar.registerTool(
                    "dependent",
                    DEPENDENT_TOOL_ID,
                    pluginTool(descriptor("plugin_dependent", "1")),
                    ToolVisibility.INTERNAL,
                    100));
    ToolFactory local =
        factory(
            LOCAL_TOOL_ID,
            descriptor("local", "1"),
            tool(descriptor("local", "1")),
            ToolVisibility.SELECTABLE,
            50);

    AgentToolRegistry catalog =
        registry(List.of(local), PluginCatalog.from(List.of(dependentPlugin, basePlugin)));

    assertEquals(
        List.of("core:local@1", "plugin:base:base", "plugin:dependent:dependent"),
        catalog.entries().subList(0, 3).stream().map(AgentToolRegistry.Entry::identity).toList());
    assertEquals(
        new ContributionId(new PluginId("dependent"), "dependent"),
        catalog.find(DEPENDENT_TOOL_ID).orElseThrow().contributionId());
    assertTrue(catalog.find(BASE_TOOL_ID).isPresent());
    assertTrue(catalog.find(new AgentToolId("test.missing")).isEmpty());
    assertEquals(
        ToolVisibility.SELECTABLE,
        catalog.find(LOCAL_TOOL_ID).orElseThrow().definition().visibility());
    assertEquals(
        ToolVisibility.INTERNAL,
        catalog.find(DEPENDENT_TOOL_ID).orElseThrow().definition().visibility());
  }

  @Test
  void appendsEnvironmentEntriesInFixedCatalogOrder() {
    ToolDescriptor hostDescriptor = descriptor("host", "1");
    AgentToolRegistry catalog =
        registry(
            List.of(
                factory(
                    LOCAL_TOOL_ID,
                    hostDescriptor,
                    tool(hostDescriptor),
                    ToolVisibility.SELECTABLE,
                    0)),
            PluginCatalog.from(List.of()));

    assertEquals("core:host@1", catalog.entries().getFirst().stableIdentity());
    assertEquals(
        EnvironmentToolCatalog.entries().stream().map(entry -> entry.definition().id()).toList(),
        catalog.entries().subList(1, catalog.entries().size()).stream()
            .map(AgentToolRegistry.Entry::id)
            .toList());
    assertEquals(
        EnvironmentToolCatalog.entries().stream()
            .map(entry -> entry.definition().descriptor().name())
            .toList(),
        catalog.entries().subList(1, catalog.entries().size()).stream()
            .map(entry -> entry.definition().descriptor().name())
            .toList());
    assertEquals(EnvironmentToolCatalog.entries().size() + 1, catalog.selectableEntries().size());
  }

  @Test
  void rejectsAgentToolIdAndModelNameCollisionsAcrossBackends() {
    ToolDescriptor uniqueName =
        new ToolDescriptor(
            "host_read",
            "1",
            "host read",
            "host_read",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentToolRegistry(
                List.of(
                    factory(
                        new AgentToolId("base.read"),
                        uniqueName,
                        tool(uniqueName),
                        ToolVisibility.SELECTABLE,
                        0)),
                PluginCatalog.from(List.of()),
                EnvironmentToolCatalog.entries()));

    ToolDescriptor duplicateName =
        new ToolDescriptor(
            "read",
            "1",
            "platform read",
            "read",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentToolRegistry(
                List.of(
                    factory(
                        new AgentToolId("test.host-read"),
                        duplicateName,
                        tool(duplicateName),
                        ToolVisibility.SELECTABLE,
                        0)),
                PluginCatalog.from(List.of()),
                EnvironmentToolCatalog.entries()));
  }

  @Test
  void ordersToolsThroughEmptyIntermediatePluginUsingTransitiveRequirements() {
    PluginDescriptor base = descriptor("base", Set.of());
    PluginDescriptor middle = descriptor("middle", Set.of(new PluginId("base")));
    PluginDescriptor top = descriptor("top", Set.of(new PluginId("middle")));
    HarnessPlugin basePlugin =
        plugin(
            base,
            registrar ->
                registrar.registerTool(
                    "base",
                    BASE_TOOL_ID,
                    pluginTool(descriptor("plugin_base", "1")),
                    ToolVisibility.SELECTABLE,
                    -100));
    HarnessPlugin middlePlugin = plugin(middle, registrar -> {});
    HarnessPlugin topPlugin =
        plugin(
            top,
            registrar ->
                registrar.registerTool(
                    "top",
                    TOP_TOOL_ID,
                    pluginTool(descriptor("plugin_top", "1")),
                    ToolVisibility.SELECTABLE,
                    100));

    AgentToolRegistry catalog =
        registry(List.of(), PluginCatalog.from(List.of(topPlugin, middlePlugin, basePlugin)));

    assertEquals(
        List.of("plugin:base:base", "plugin:top:top"),
        catalog.entries().subList(0, 2).stream().map(AgentToolRegistry.Entry::identity).toList());
  }

  @Test
  void createsHostToolAndRejectsDescriptorDriftOrPluginEntry() {
    ToolDescriptor descriptor = descriptor("local", "1");
    Tool expected = tool(descriptor);
    AgentToolRegistry catalog =
        registry(
            List.of(factory(LOCAL_TOOL_ID, descriptor, expected, ToolVisibility.SELECTABLE, 0)),
            PluginCatalog.from(List.of()));
    AgentToolRegistry.Entry entry = catalog.find(LOCAL_TOOL_ID).orElseThrow();
    assertSame(expected, catalog.createHostTool(entry));

    ToolDescriptor drifted = descriptor("local", "1");
    ToolFactory driftingFactory =
        factory(
            LOCAL_TOOL_ID,
            descriptor,
            toolWithDifferentDescriptor(drifted),
            ToolVisibility.SELECTABLE,
            0);
    AgentToolRegistry driftingCatalog =
        registry(List.of(driftingFactory), PluginCatalog.from(List.of()));
    IllegalArgumentException drift =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                driftingCatalog.createHostTool(driftingCatalog.find(LOCAL_TOOL_ID).orElseThrow()));
    assertTrue(drift.getMessage().contains("does not match frozen"));

    HarnessPlugin plugin =
        plugin(
            descriptor("plugin", Set.of()),
            registrar ->
                registrar.registerTool(
                    "tool",
                    PLUGIN_TOOL_ID,
                    pluginTool(descriptor("plugin_tool", "1")),
                    ToolVisibility.SELECTABLE));
    AgentToolRegistry.Entry pluginEntry =
        registry(List.of(), PluginCatalog.from(List.of(plugin))).find(PLUGIN_TOOL_ID).orElseThrow();
    assertEquals(AgentToolBackend.PLUGIN, pluginEntry.definition().backend());
    assertTrue(pluginEntry.hostFactory() == null);
    assertEquals("plugin:plugin:tool", pluginEntry.stableIdentity());
    assertEquals(new PluginId("plugin"), pluginEntry.pluginId());
    IllegalArgumentException notLocal =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry(List.of(), PluginCatalog.from(List.of(plugin)))
                    .createHostTool(pluginEntry));
    assertInstanceOf(IllegalArgumentException.class, notLocal);
  }

  @Test
  void rejectsDuplicateNames() {
    ToolDescriptor duplicate = descriptor("duplicate", "1");
    IllegalArgumentException duplicateError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AgentToolRegistry(
                    List.of(
                        factory(
                            DUPLICATE_FIRST_ID,
                            duplicate,
                            tool(duplicate),
                            ToolVisibility.SELECTABLE,
                            0),
                        factory(
                            DUPLICATE_SECOND_ID,
                            duplicate,
                            tool(duplicate),
                            ToolVisibility.SELECTABLE,
                            0)),
                    PluginCatalog.from(List.of()),
                    EnvironmentToolCatalog.entries()));
    assertTrue(duplicateError.getMessage().contains("duplicate Agent tool name"));
  }

  /** 验证启动时 Host 与 Plugin 贡献共享同一个 AgentToolId 命名空间。 */
  @Test
  void rejectsAgentToolIdCollisionBetweenHostAndPlugin() {
    HarnessPlugin plugin =
        plugin(
            descriptor("plugin", Set.of()),
            registrar ->
                registrar.registerTool(
                    "plugin-tool",
                    LOCAL_TOOL_ID,
                    pluginTool(descriptor("plugin-tool", "1")),
                    ToolVisibility.SELECTABLE));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AgentToolRegistry(
                    List.of(
                        factory(
                            LOCAL_TOOL_ID,
                            descriptor("local", "1"),
                            tool(descriptor("local", "1")),
                            ToolVisibility.SELECTABLE,
                            0)),
                    PluginCatalog.from(List.of(plugin)),
                    EnvironmentToolCatalog.entries()));
    assertTrue(error.getMessage().contains("duplicate AgentToolId"));
    assertTrue(error.getMessage().contains(LOCAL_TOOL_ID.toString()));
  }

  @Test
  void exposesEntryProvenanceAndRejectsInvalidEntryShapes() {
    ToolDescriptor descriptor = descriptor("local", "1");
    ToolFactory hostFactory =
        factory(LOCAL_TOOL_ID, descriptor, tool(descriptor), ToolVisibility.SELECTABLE, 0);
    AgentToolRegistry registry = registry(List.of(hostFactory), PluginCatalog.from(List.of()));
    AgentToolRegistry.Entry host = registry.find(LOCAL_TOOL_ID).orElseThrow();
    assertEquals(AgentToolBackend.HOST, host.definition().backend());
    assertSame(hostFactory, host.hostFactory());
    assertEquals("core:local@1", host.stableIdentity());
    assertEquals(LOCAL_TOOL_ID, host.id());
    assertTrue(host.pluginContribution() == null);
    assertTrue(host.capability() == null);

    HarnessPlugin plugin =
        plugin(
            descriptor("plugin", Set.of()),
            registrar ->
                registrar.registerTool(
                    "tool",
                    PLUGIN_TOOL_ID,
                    pluginTool(descriptor("plugin_tool", "1")),
                    ToolVisibility.SELECTABLE));
    AgentToolRegistry pluginRegistry = registry(List.of(), PluginCatalog.from(List.of(plugin)));
    AgentToolRegistry.Entry pluginEntry = pluginRegistry.find(PLUGIN_TOOL_ID).orElseThrow();
    assertEquals(AgentToolBackend.PLUGIN, pluginEntry.definition().backend());
    assertEquals("plugin:plugin:tool", pluginEntry.stableIdentity());
    assertEquals(new PluginId("plugin"), pluginEntry.pluginId());
    assertTrue(pluginEntry.hostFactory() == null);
    assertTrue(pluginEntry.capability() == null);

    AgentToolRegistry.Entry environment =
        registry(List.of(), PluginCatalog.from(List.of()))
            .find(EnvironmentToolCatalog.entries().getFirst().definition().id())
            .orElseThrow();
    assertEquals(AgentToolBackend.ENVIRONMENT_CAPABILITY, environment.definition().backend());
    assertEquals(
        EnvironmentToolCatalog.entries().getFirst().capability(), environment.capability());
    assertTrue(environment.hostFactory() == null);
    assertTrue(environment.pluginContribution() == null);

    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentToolRegistry.Entry(host.definition(), 0, " ", hostFactory, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentToolRegistry.Entry(
                host.definition(), 0, "host", hostFactory, null, environment.capability()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentToolRegistry.Entry(pluginEntry.definition(), 0, "plugin", null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentToolRegistry.Entry(
                environment.definition(), 0, "environment", hostFactory, null, null));
    EnvironmentCapabilityDescriptor drifted =
        new EnvironmentCapabilityDescriptor(
            environment.capability().id(),
            environment.capability().version(),
            environment.capability().inputSchema(),
            environment.capability().timeout().plusMillis(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentToolRegistry.Entry(
                environment.definition(), 0, "environment", null, null, drifted));
  }

  private static AgentToolRegistry registry(
      List<? extends ToolFactory> factories, PluginCatalog pluginCatalog) {
    return new AgentToolRegistry(factories, pluginCatalog, EnvironmentToolCatalog.entries());
  }

  private static PluginDescriptor descriptor(String id, Set<PluginId> requires) {
    return new PluginDescriptor(new PluginId(id), id, "1", requires);
  }

  private static ToolDescriptor descriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
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
      AgentToolId id,
      ToolDescriptor descriptor,
      Tool tool,
      ToolVisibility visibility,
      int priority) {
    return new ToolFactory() {
      @Override
      public AgentToolDefinition definition() {
        return new AgentToolDefinition(id, descriptor, visibility, AgentToolBackend.HOST);
      }

      @Override
      public Tool create() {
        return tool;
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
