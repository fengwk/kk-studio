package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.harness.tool.CompositeRuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.harness.tool.HarnessToolCatalogAdapter;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** {@link ToolExecutionGateway#preflight}：三个 permission 状态的映射，且不改写冻结 request。 */
class ToolExecutionGatewayPreflightTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");
  private static final ToolDescriptor PREFLIGHT_DESCRIPTOR = preflightDescriptor();

  private static ToolDescriptor preflightDescriptor() {
    return new ToolDescriptor(
        "demo",
        "1",
        "description of demo",
        "demo",
        new InputSchema(
            "arguments", Map.of("path", new StringSchema("Target path")), Set.of("path"), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  @Test
  void allowWhenRulesAllow() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.ALLOW);
    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void askWhenRulesAskWithBoundedPreviewReason() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.ASK);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertTrue(
        ask.reason().startsWith(ToolGatewayTestSupport.TEST_TOOL_ID.value() + " requires approval"),
        ask.reason());
    assertTrue(ask.reason().contains(ToolGatewayTestSupport.TEST_TOOL_ID.value()), ask.reason());
    assertTrue(ask.reason().contains("\"path\":\"/tmp/x\""), ask.reason());
    assertEquals(ask.reason(), ask.reason().strip());
    assertTrue(ask.reason().length() <= 1024);
  }

  @Test
  void denyWhenRulesDenyWithStableKindAndMessage() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.DENY);
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("PERMISSION_DENIED", deny.error().kind());
    assertEquals("Tool permission was denied.", deny.error().message());
  }

  @Test
  void preflightUsesFrozenRegistryIdInsteadOfModelVisibleName() {
    ToolGatewayTestSupport.FakeTool tool =
        new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR);
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                "*",
                List.of(new PermissionRule("*", PermissionAction.ASK)),
                "demo",
                List.of(new PermissionRule("*", PermissionAction.ALLOW)),
                ToolGatewayTestSupport.TEST_TOOL_ID.value(),
                List.of(new PermissionRule("*", PermissionAction.DENY))),
            false);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            settings);

    ToolGateway.Deny deny =
        assertInstanceOf(
            ToolGateway.Deny.class,
            gateway.preflight(
                new ToolInvocationRequest(
                    new ToolCall("call-1", "demo", "{\"path\":\"/tmp/x\"}"),
                    new ToolBinding(
                        hostDefinition(PREFLIGHT_DESCRIPTOR),
                        new ContributorBinding("test", "host-tool", List.of()),
                        false,
                        null))));

    assertEquals(ToolExecutionGateway.PERMISSION_DENIED_KIND, deny.error().kind());
    assertEquals("Tool permission was denied.", deny.error().message());
  }

  @Test
  void unknownAndMismatchedDescriptorsAreDeniedBeforePermissionEvaluation() {
    PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
    HarnessCatalog catalog =
        ToolGatewayTestSupport.defaultCatalog(
            new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR));
    ToolExecutionGateway gateway =
        new ToolExecutionGateway(
            new HarnessToolCatalogAdapter(catalog),
            catalog,
            ToolGatewayTestSupport.FAILING_CONTRIBUTOR_BRANCH_LOADER,
            new ToolGatewayTestSupport.FakeTransport(),
            evaluator,
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            new ToolGatewayTestSupport.FakeResourceStore(),
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
            ToolGatewayTestSupport.TEST_CLOCK,
            new ConcurrencyAdmission(Integer.MAX_VALUE));

    ToolDescriptor unknown = ToolGatewayTestSupport.hostDescriptor("missing");
    ToolGateway.Deny unknownDeny =
        assertInstanceOf(
            ToolGateway.Deny.class,
            gateway.preflight(
                new ToolInvocationRequest(
                    new ToolCall("unknown", "missing", "{}"),
                    new ToolBinding(
                        new AgentToolDefinition(
                            new AgentToolId("test.missing"), unknown, ToolVisibility.SELECTABLE),
                        new ContributorBinding("test", "missing", List.of()),
                        false,
                        null))));
    assertEquals(ToolExecutionGateway.TOOL_NOT_FOUND_KIND, unknownDeny.error().kind());
    assertEquals(
        "Frozen tool definition test.missing is not registered.", unknownDeny.error().message());

    ToolDescriptor mismatched = ToolGatewayTestSupport.hostDescriptor("demo");
    ToolGateway.Deny mismatchDeny =
        assertInstanceOf(
            ToolGateway.Deny.class,
            gateway.preflight(
                new ToolInvocationRequest(
                    new ToolCall("mismatch", "demo", "{}"),
                    new ToolBinding(
                        hostDefinition(mismatched),
                        new ContributorBinding("test", "host-tool", List.of()),
                        false,
                        null))));
    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, mismatchDeny.error().kind());
    assertEquals(
        "Frozen tool definition test.host-tool does not match its catalog definition.",
        mismatchDeny.error().message());
    verifyNoInteractions(evaluator);
  }

  @Test
  void askReasonLongerThan1024IsTruncatedAtCodePointBoundary() {
    Path hugeWorkdir = Path.of("/w/" + "\uD83D\uDE00".repeat(520) + "/deep");
    ToolGateway.PreflightResult result =
        preflightTruncation(PermissionAction.ASK, hugeWorkdir, Path.of("/env-root"));
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertTrue(ask.reason().length() <= 1024, ask.reason());
    assertTrue(ask.reason().endsWith("..."), ask.reason());
    assertFalse(hasLoneSurrogate(ask.reason()), ask.reason());
  }

  @Test
  void preflightIsSideEffectFreeAndKeepsFrozenRequestUntouched() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog(tool);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(catalog, transport, store, executor);
      ToolInvocationRequest request = ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);
      gateway.preflight(request);
      assertTrue(transport.invocations.isEmpty());
      assertTrue(store.puts.isEmpty());
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(request),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
      startedResult.handle().activate();
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (tool.requests.isEmpty() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(1, tool.requests.size());
      assertSame(request.call(), tool.requests.get(0).call());
      assertEquals(request.binding().descriptor(), tool.requests.get(0).descriptor());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void environmentWorkspaceBecomesDefaultWorkdirForAskPreview() {
    EnvironmentBinding binding =
        new EnvironmentBinding(
            EnvironmentId.parse("11111111-1111-1111-1111-111111111111"), "repo/sub");
    ToolGateway.PreflightResult result =
        environmentPreflight(binding, "{\"path\":\"src/Main.java\"}", PermissionAction.ASK);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertTrue(ask.reason().contains("/environment-root/repo/sub (default)"), ask.reason());
  }

  @Test
  void environmentWorkspaceIsTheOnlyPathRuleBase() {
    EnvironmentBinding binding =
        new EnvironmentBinding(
            EnvironmentId.parse("11111111-1111-1111-1111-111111111111"), "repo/sub");
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                "*",
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/**", PermissionAction.DENY))),
            false);
    ToolGateway.PreflightResult result =
        environmentPreflight(binding, "{\"path\":\"src/Main.java\"}", settings);
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("PERMISSION_DENIED", deny.error().kind());

    ToolSettings rootRelativeRule =
        new ToolSettings(
            Map.of(
                "*",
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("repo/sub/**", PermissionAction.DENY))),
            false);
    ToolGateway.PreflightResult rootRelativeResult =
        environmentPreflight(binding, "{\"path\":\"src/Main.java\"}", rootRelativeRule);
    assertInstanceOf(ToolGateway.Ask.class, rootRelativeResult);
  }

  @Test
  void platformPreflightKeepsServerDefaultWorkdir() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.ASK);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertTrue(ask.reason().contains("/workspace (default)"), ask.reason());
  }

  private static ToolGateway.PreflightResult preflight(PermissionAction action) {
    return preflight(
        action, ToolGatewayTestSupport.WORKDIR, ToolGatewayTestSupport.ENVIRONMENT_ROOT);
  }

  private static ToolGateway.PreflightResult environmentPreflight(
      EnvironmentBinding environment, String argumentsJson, PermissionAction action) {
    return environmentPreflight(
        environment, argumentsJson, ToolGatewayTestSupport.settings(action));
  }

  private static ToolGateway.PreflightResult environmentPreflight(
      EnvironmentBinding environment, String argumentsJson, ToolSettings settings) {
    ToolContribution contribution =
        ToolGatewayTestSupport.defaultCatalog().findTool(BuiltinToolIds.READ).orElseThrow();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            settings,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT);
    ContributorBinding contributor =
        new ContributorBinding(
            contribution.id().contributorId().value(), contribution.id().localName(), List.of());
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "read", argumentsJson),
            new ToolBinding(contribution.definition(), contributor, true, environment));
    return gateway.preflight(request);
  }

  private static ToolGateway.PreflightResult preflight(
      PermissionAction action, Path workdir, Path environmentRoot) {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(
                new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.settings(action),
            workdir,
            environmentRoot);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "demo", "{\"path\":\"/tmp/x\"}"),
            new ToolBinding(
                hostDefinition(PREFLIGHT_DESCRIPTOR),
                new ContributorBinding("test", "host-tool", List.of()),
                false,
                null));
    return gateway.preflight(request);
  }

  private static ToolDescriptor truncationDescriptor() {
    return new ToolDescriptor(
        "x",
        "1",
        "description of x",
        "x",
        new InputSchema(
            "arguments", Map.of("path", new StringSchema("Target path")), Set.of("path"), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  private static ToolGateway.PreflightResult preflightTruncation(
      PermissionAction action, Path workdir, Path environmentRoot) {
    ToolDescriptor descriptor = truncationDescriptor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(new ToolGatewayTestSupport.FakeTool(descriptor)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.settings(action),
            workdir,
            environmentRoot);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "x", "{\"path\":\"/tmp/x\"}"),
            new ToolBinding(
                hostDefinition(descriptor),
                new ContributorBinding("test", "host-tool", List.of()),
                false,
                null));
    return gateway.preflight(request);
  }

  @Test
  void preflightAcceptsDynamicMcpTool() {
    // 意图：验证动态 MCP 工具在通过 RuntimeToolCatalog 聚合后能够顺利通过 Gateway preflight
    McpServerRepository repo = mock(McpServerRepository.class);
    McpToolClientFactory factory = mock(McpToolClientFactory.class);
    McpToolCatalog mcpCatalog = new McpToolCatalog(repo, factory);

    UUID serverId = UUID.randomUUID();
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("mcp-server");
    server.setUrl("http://localhost:8080");
    server.setTimeoutMillis(5000L);
    server.setVersion(1L);

    UUID toolId = UUID.randomUUID();
    McpTool mcpTool = new McpTool();
    mcpTool.setId(toolId);
    mcpTool.setServerId(serverId);
    mcpTool.setSourceName("echo");
    mcpTool.setModelName("mcp_server_echo");
    mcpTool.setDescription("echo tool");
    mcpTool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");

    when(repo.getToolById(toolId)).thenReturn(Optional.of(mcpTool));
    when(repo.getById(serverId)).thenReturn(Optional.of(server));
    when(repo.listAllServers()).thenReturn(List.of(server));
    when(repo.listTools(serverId)).thenReturn(List.of(mcpTool));

    HarnessCatalog harnessCatalog = HarnessCatalog.from(List.of());
    RuntimeToolCatalog toolCatalog =
        new CompositeRuntimeToolCatalog(
            List.of(new HarnessToolCatalogAdapter(harnessCatalog), mcpCatalog));

    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            toolCatalog,
            harnessCatalog,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            new ConcurrencyAdmission(Integer.MAX_VALUE));

    ToolContribution contribution =
        toolCatalog.findTool(McpStableIds.agentToolId(toolId)).orElseThrow();
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-mcp", "mcp_server_echo", "{}"),
            new ToolBinding(
                contribution.definition(),
                new ContributorBinding(
                    contribution.id().contributorId().value(),
                    contribution.id().localName(),
                    List.of()),
                false,
                null));

    ToolGateway.PreflightResult result = gateway.preflight(request);
    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  private static AgentToolDefinition hostDefinition(ToolDescriptor descriptor) {
    return new AgentToolDefinition(
        ToolGatewayTestSupport.TEST_TOOL_ID, descriptor, ToolVisibility.SELECTABLE);
  }

  private static boolean hasLoneSurrogate(String value) {
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          return true;
        }
        i++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }
}
