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

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
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
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.harness.tool.CompositeRuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.harness.tool.HarnessToolCatalogAdapter;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;

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
        "description of demo",
        "demo",
        new InputSchema(
            "arguments",
            Map.of("path", new StringSchema("Target path"), "workdir", new StringSchema("Workdir")),
            Set.of("path", "workdir"),
            false),
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
        ask.reason().startsWith(PREFLIGHT_DESCRIPTOR.name() + " requires approval"), ask.reason());
    assertTrue(ask.reason().contains(PREFLIGHT_DESCRIPTOR.name()), ask.reason());
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
  void preflightIgnoresRulesKeyedByContributionLocalName() {
    // 工具的唯一身份是模型可见 name；以 ContributionId localName 为 key 的规则不得影响该工具的求值结果。
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                "*", List.of(new PermissionRule("*", PermissionAction.ASK)),
                "host-tool", List.of(new PermissionRule("*", PermissionAction.DENY))),
            false);
    ToolGateway.PreflightResult result =
        preflight(
            new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR),
            "{\"path\":\"/tmp/x\",\"workdir\":\"/tmp\"}",
            settings);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertTrue(ask.reason().startsWith(PREFLIGHT_DESCRIPTOR.name()), ask.reason());
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
                        new AgentToolDefinition(unknown, ToolVisibility.SELECTABLE),
                        new ContributorBinding("test", "missing", List.of()),
                        false,
                        null))));
    assertEquals(ToolExecutionGateway.TOOL_NOT_FOUND_KIND, unknownDeny.error().kind());
    assertEquals(
        "Frozen tool definition missing is not registered.", unknownDeny.error().message());

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
        "Frozen tool definition demo does not match its catalog definition.",
        mismatchDeny.error().message());
    verifyNoInteractions(evaluator);
  }

  /** 未选择 Environment 的环境工具必须在 permission 判定之前短路：返回稳定 kind，且不弹审批、不读权限策略。 */
  @Test
  void unselectedEnvironmentToolIsDeniedBeforePermissionEvaluation() {
    PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
    ToolGatewayTestSupport.CountingToolSettingsProvider settingsProvider =
        new ToolGatewayTestSupport.CountingToolSettingsProvider(
            ToolGatewayTestSupport.settings(PermissionAction.ASK));
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog();
    ToolExecutionGateway gateway =
        new ToolExecutionGateway(
            new HarnessToolCatalogAdapter(catalog),
            catalog,
            ToolGatewayTestSupport.FAILING_CONTRIBUTOR_BRANCH_LOADER,
            new ToolGatewayTestSupport.FakeTransport(),
            evaluator,
            settingsProvider,
            new ToolGatewayTestSupport.FakeResourceStore(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
            ToolGatewayTestSupport.TEST_CLOCK,
            new ConcurrencyAdmission(Integer.MAX_VALUE));

    ToolGateway.PreflightResult result =
        gateway.preflight(ToolGatewayTestSupport.unselectedEnvironmentRequest("call-1"));

    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("ENVIRONMENT_NOT_SELECTED", deny.error().kind());
    assertTrue(deny.error().message().contains("select an Environment"), deny.error().message());
    // 权限策略未被读取，evaluator 也未被调用：绝不会把该错误伪装成审批或权限拒绝。
    verifyNoInteractions(evaluator);
    assertEquals(0, settingsProvider.readCount());
  }

  @Test
  void askReasonLongerThan1024IsTruncatedAtCodePointBoundary() {
    // preview 的 workdir 只来自该次 arguments；超长 workdir 用于压出截断路径。
    String hugeWorkdir = "/w/" + "\uD83D\uDE00".repeat(520) + "/deep";
    ToolGateway.PreflightResult result =
        preflightTruncation(
            PermissionAction.ASK, "{\"path\":\"/tmp/x\",\"workdir\":\"" + hugeWorkdir + "\"}");
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

  /** 环境工具只绑定 canonical EnvironmentId；permission 坐标只来自该次 arguments 的 workdir。 */
  @Test
  void environmentToolAskPreviewShowsCallWorkdirInsteadOfAnyDefault() {
    EnvironmentId environmentId = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    ToolGateway.PreflightResult result =
        environmentPreflight(
            environmentId,
            "{\"path\":\"src/Main.java\",\"workdir\":\"/repo/sub\"}",
            PermissionAction.ASK);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertTrue(ask.reason().contains(" in /repo/sub"), ask.reason());
    assertFalse(ask.reason().contains("(default)"), ask.reason());
  }

  /** 没有 workdir 语义的调用不显示任何虚构默认目录。 */
  @Test
  void toolWithoutWorkdirDoesNotRenderFabricatedDefaultDirectory() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.settings(PermissionAction.ASK));
    ToolGateway.PreflightResult result =
        gateway.preflight(ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR));
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertFalse(ask.reason().contains("(default)"), ask.reason());
    assertFalse(ask.reason().contains(" in "), ask.reason());
  }

  /** path 规则坐标系是该次调用 explicit workdir：绝对 target 词法 relativize，与 Environment root 无关。 */
  @Test
  void pathRulesAreGradedAgainstCallWorkdirOnly() {
    EnvironmentId environmentId = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                "*",
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/**", PermissionAction.DENY))),
            false);
    ToolGateway.PreflightResult result =
        environmentPreflight(
            environmentId,
            "{\"path\":\"/repo/sub/src/Main.java\",\"workdir\":\"/repo/sub\"}",
            settings);
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("PERMISSION_DENIED", deny.error().kind());

    // 同一 workdir 之外的绝对路径不会命中 workdir 相对规则。
    ToolGateway.PreflightResult outside =
        environmentPreflight(
            environmentId,
            "{\"path\":\"/elsewhere/src/Main.java\",\"workdir\":\"/repo/sub\"}",
            settings);
    assertInstanceOf(ToolGateway.Ask.class, outside);
  }

  private static ToolGateway.PreflightResult environmentPreflight(
      EnvironmentId environmentId, String argumentsJson, PermissionAction action) {
    return environmentPreflight(
        environmentId, argumentsJson, ToolGatewayTestSupport.settings(action));
  }

  private static ToolGateway.PreflightResult environmentPreflight(
      EnvironmentId environmentId, String argumentsJson, ToolSettings settings) {
    ToolContribution contribution =
        ToolGatewayTestSupport.defaultCatalog().findTool("read").orElseThrow();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            settings);
    ContributorBinding contributor =
        new ContributorBinding(
            contribution.id().contributorId().value(), contribution.id().localName(), List.of());
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "read", argumentsJson),
            new ToolBinding(contribution.definition(), contributor, true, environmentId));
    return gateway.preflight(request);
  }

  private static ToolGateway.PreflightResult preflight(PermissionAction action) {
    return preflight(
        new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR),
        "{\"path\":\"/tmp/x\",\"workdir\":\"/tmp\"}",
        ToolGatewayTestSupport.settings(action));
  }

  private static ToolGateway.PreflightResult preflight(
      ToolGatewayTestSupport.FakeTool tool, String argumentsJson, ToolSettings settings) {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            settings);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", PREFLIGHT_DESCRIPTOR.name(), argumentsJson),
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
        "description of x",
        "x",
        new InputSchema(
            "arguments",
            Map.of("path", new StringSchema("Target path"), "workdir", new StringSchema("Workdir")),
            Set.of("path", "workdir"),
            false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  private static ToolGateway.PreflightResult preflightTruncation(
      PermissionAction action, String argumentsJson) {
    ToolDescriptor descriptor = truncationDescriptor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(new ToolGatewayTestSupport.FakeTool(descriptor)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.settings(action));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "x", argumentsJson),
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
    McpToolCatalog mcpCatalog = new McpToolCatalog(repo, mock(ExecutorService.class));

    UUID serverId = UUID.randomUUID();
    McpServer server = new McpServer();
    server.setId(serverId);
    server.setName("mcp-server");
    server.setConnectionType(McpConnectionType.REMOTE);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setDiscoveredVersion(1L);
    server.setEnabled(true);
    server.setVersion(1L);
    server.setConnectionConfig("{\"url\":\"http://localhost:8080\",\"headers\":{}}");
    server.setTimeoutMillis(5000L);

    UUID toolId = UUID.randomUUID();
    McpTool mcpTool = new McpTool();
    mcpTool.setId(toolId);
    mcpTool.setServerId(serverId);
    mcpTool.setSourceName("echo");
    mcpTool.setModelName("mcp_server_echo");
    mcpTool.setDescription("echo tool");
    mcpTool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    mcpTool.setAvailable(true);
    mcpTool.setSchemaRevision(1L);

    when(repo.getAvailableToolByModelName("mcp_server_echo")).thenReturn(Optional.of(mcpTool));
    when(repo.getById(serverId)).thenReturn(Optional.of(server));
    when(repo.listAllServers()).thenReturn(List.of(server));
    when(repo.listAvailableTools(serverId)).thenReturn(List.of(mcpTool));

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

    ToolContribution contribution = toolCatalog.findTool("mcp_server_echo").orElseThrow();
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
    return new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE);
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
