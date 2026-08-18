package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.plugin.PluginBranchViewLoader;
import fun.fengwk.kkstudio.harness.plugin.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.plugin.BranchView;
import fun.fengwk.kkstudio.harness.plugin.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.PluginId;
import fun.fengwk.kkstudio.harness.plugin.PluginStateDeclaration;
import fun.fengwk.kkstudio.harness.plugin.PluginStateMode;
import fun.fengwk.kkstudio.harness.plugin.PluginTool;
import fun.fengwk.kkstudio.harness.plugin.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.PluginToolResult;
import fun.fengwk.kkstudio.harness.plugin.ToolVisibility;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 插件 Tool 的 frozen provenance、intent 校验顺序与 effects 交付测试。 */
class CoreToolGatewayPluginTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final PluginId PLUGIN_ID = new PluginId("goal");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "plugin_tool",
          "1",
          ToolType.PLATFORM,
          "plugin tool",
          "plugin_tool",
          new ToolParamsSchema("args", Map.of(), Set.of(), false),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  @Test
  void pluginToolPassesPermissionPreflightWithoutToolFactoriesRegistration() {
    Fixture fixture = fixture(pluginTool(PluginToolResult.withoutIntents(result(List.of()))));

    ToolGateway.PreflightResult result =
        fixture.gateway.preflight(fixture.execution("write").request(), false);

    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void validPluginAppendIsValidatedThenDeliveredAsAPluginOwnedEffect() {
    CustomEntryPayload payload =
        new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"ship\"}");
    PluginTool tool =
        pluginTool(
            new PluginToolResult(
                result(List.of(new TextToolContent("ok"))),
                List.of(new AppendCustomEntry(payload))));
    Fixture fixture = fixture(tool);

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    started.handle().activate();
    fixture.executor.runAll();

    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Succeeded.class,
            fixture.listener.events.get(0));
    assertEquals(List.of(payload), succeeded.effects().customEntries());
    assertEquals(ToolGatewayTestSupport.ASSISTANT_ENTRY_ID, fixture.loadedAssistantId.get());
  }

  @Test
  void frozenContributionMismatchIsRejectedBeforeExecution() {
    Fixture fixture = fixture(pluginTool(PluginToolResult.withoutIntents(result(List.of()))));
    ToolGateway.StartResult result =
        fixture.gateway.start(fixture.execution("other"), fixture.listener);
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals(CoreToolGateway.TOOL_NOT_FOUND_KIND, rejected.error().kind());
    assertTrue(fixture.listener.events.isEmpty());
  }

  @Test
  void invalidIntentFailsBeforeResultExternalization() {
    CustomEntryPayload forged =
        new CustomEntryPayload("other", "state", 1, "{\"objective\":\"forged\"}");
    PluginTool tool =
        pluginTool(
            new PluginToolResult(
                result(
                    List.of(new BinaryToolContent("application/octet-stream", new byte[] {1, 2}))),
                List.of(new AppendCustomEntry(forged))));
    Fixture fixture = fixture(tool);

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    started.handle().activate();
    fixture.executor.runAll();

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Failed.class,
            fixture.listener.events.get(0));
    assertEquals(CoreToolGateway.PLUGIN_CONTRACT_VIOLATION_KIND, failed.failure().error().kind());
    assertTrue(
        fixture.resourceStore.puts.isEmpty(), "invalid intents must fail before externalize");
  }

  private static PluginTool pluginTool(PluginToolResult result) {
    return new PluginTool() {
      @Override
      public ToolDescriptor descriptor() {
        return DESCRIPTOR;
      }

      @Override
      public List<PluginStateDeclaration> stateAccesses() {
        return List.of(new PluginStateDeclaration("state", PluginStateMode.WRITE));
      }

      @Override
      public PluginToolResult execute(PluginToolContext context, ToolCall call) {
        assertEquals("plugin_tool", call.toolName());
        assertEquals(new UUID(0L, 1L), context.branch().path().head().id());
        return result;
      }
    };
  }

  private static ToolResult result(List<ToolContent> contents) {
    return new ToolResult("call-1", contents, false, "{}");
  }

  private static Fixture fixture(PluginTool tool) {
    HarnessPlugin plugin =
        HarnessPlugin.of(
            new PluginDescriptor(PLUGIN_ID, "Goal", "1"),
            registrar -> {
              registrar.registerCustomEntryType("state-type", "state");
              registrar.registerTool("write", tool, ToolVisibility.SELECTABLE);
            });
    PluginCatalog catalog = PluginCatalog.from(List.of(plugin));
    AtomicReference<UUID> loadedAssistantId = new AtomicReference<>();
    PluginBranchViewLoader loader =
        assistantEntryId -> {
          loadedAssistantId.set(assistantEntryId);
          return rootBranch();
        };
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore resourceStore =
        new ToolGatewayTestSupport.FakeResourceStore();
    CoreToolGateway gateway =
        new CoreToolGateway(
            new ToolFactories(List.of()),
            catalog,
            loader,
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            resourceStore,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            executor,
            ToolGatewayTestSupport.CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC));
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    listener.store = resourceStore;
    return new Fixture(gateway, executor, resourceStore, listener, loadedAssistantId);
  }

  private static BranchView rootBranch() {
    UUID id = new UUID(0L, 1L);
    Entry root =
        new Entry(
            id,
            id,
            null,
            new RootPayload(
                new BranchSettings(
                    null,
                    "assistant",
                    new ModelSelection("provider", "model", "default"),
                    List.of())),
            NOW);
    return new BranchView(new EntryPath(List.of(root)));
  }

  private record Fixture(
      CoreToolGateway gateway,
      ToolGatewayTestSupport.ManualExecutor executor,
      ToolGatewayTestSupport.FakeResourceStore resourceStore,
      ToolGatewayTestSupport.RecordingListener listener,
      AtomicReference<UUID> loadedAssistantId) {

    ToolGateway.Execution execution(String contributionLocalName) {
      PluginToolBinding plugin =
          new PluginToolBinding(
              "goal",
              contributionLocalName,
              List.of(new PluginStateAccess("state", PluginStateAccessMode.WRITE)));
      ToolInvocationRequest request =
          new ToolInvocationRequest(
              new ToolCall("call-1", "plugin_tool", "{}"),
              new ToolBinding(DESCRIPTOR, ToolType.PLATFORM, null, plugin));
      return new ToolGateway.Execution(
          ToolGatewayTestSupport.INVOCATION_ID,
          ToolGatewayTestSupport.THREAD_ID,
          ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
          1,
          request);
    }
  }
}
