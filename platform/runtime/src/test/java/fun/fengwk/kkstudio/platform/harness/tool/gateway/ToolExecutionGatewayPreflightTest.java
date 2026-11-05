package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.platform.harness.tool.VfsEditReviewSourceStub;
import fun.fengwk.kkstudio.platform.harness.tool.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionAction;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionRule;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettings;
import fun.fengwk.kkstudio.platform.vfs.VfsEditRejectedException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link ToolExecutionGateway#preflight}：三个 permission 状态的映射，且不改写冻结 request。 */
class ToolExecutionGatewayPreflightTest {

  private static final ToolGateway.ExecutionScope SCOPE =
      ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW);

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
  void askWhenRulesAskWithFixedReason() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.ASK);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertEquals("Permission rules require approval", ask.reason());
  }

  @Test
  void denyWhenRulesDenyWithStableKindAndMessage() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.DENY);
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("PERMISSION_DENIED", deny.error().kind());
    assertTrue(
        deny.error().message().contains("Tool permission was denied"), deny.error().message());
    // 权限拒绝是派发前确定性失败：必须明确未执行。
    assertTrue(
        deny.error().message().contains("The tool was not executed."), deny.error().message());
  }

  @Test
  void preflightIgnoresRulesKeyedByContributionLocalName() {
    // 工具的唯一身份是模型可见 name；以 ContributionId localName 为 key 的规则不得影响该工具的求值结果。
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                "*", List.of(new PermissionRule("*", PermissionAction.ASK)),
                "host-tool", List.of(new PermissionRule("*", PermissionAction.DENY))));
    ToolGateway.PreflightResult result =
        preflight(
            new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR),
            "{\"path\":\"/tmp/x\",\"workdir\":\"/tmp\"}",
            settings);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertEquals("Permission rules require approval", ask.reason());
  }

  @Test
  void unknownAndMismatchedDescriptorsAreDeniedBeforePermissionEvaluation() {
    PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
    HarnessCatalog catalog =
        ToolGatewayTestSupport.defaultCatalog(
            new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR));
    ToolGatewayTestSupport.FakeResourceStager stager =
        new ToolGatewayTestSupport.FakeResourceStager();
    ToolExecutionGateway gateway =
        new ToolExecutionGateway(
            TestRuntimeToolCatalogSource.of(catalog),
            new ToolGatewayTestSupport.FakeTransport(),
            VfsEditReviewSourceStub.INSTANCE,
            evaluator,
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            stager,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
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
                        EnvironmentSupport.NONE,
                        null,
                        null)),
                SCOPE));
    assertEquals(ToolErrorClassifier.TOOL_NOT_FOUND_KIND, unknownDeny.error().kind());
    assertTrue(
        unknownDeny.error().message().contains("Tool missing is no longer available"),
        unknownDeny.error().message());
    assertTrue(
        unknownDeny.error().message().contains("The tool was not executed."),
        unknownDeny.error().message());

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
                        EnvironmentSupport.NONE,
                        null,
                        null)),
                SCOPE));
    assertEquals(ToolErrorClassifier.TOOL_DEFINITION_MISMATCH_KIND, mismatchDeny.error().kind());
    assertTrue(
        mismatchDeny.error().message().contains("Tool demo changed after this call was planned"),
        mismatchDeny.error().message());
    assertTrue(
        mismatchDeny.error().message().contains("The tool was not executed."),
        mismatchDeny.error().message());
    verifyNoInteractions(evaluator);
  }

  @Test
  void askWithLongUnicodeArgumentsKeepsOriginalParameters() {
    // 超长目录与 Unicode 参数不进入 reason，审批所用原始参数必须完整保留。
    String hugeWorkdir = "/w/" + "目录\uD83D\uDE00".repeat(520) + "/deep";
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("path", hugeWorkdir + "/文件\uD83D\uDE00.txt");
    arguments.put("workdir", hugeWorkdir);
    ObjectNode original = arguments.deepCopy();
    String argumentsJson = JsonValues.write(arguments);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", PREFLIGHT_DESCRIPTOR.name(), argumentsJson),
            new ToolBinding(
                hostDefinition(PREFLIGHT_DESCRIPTOR),
                new ContributorBinding("test", "host-tool", List.of()),
                EnvironmentSupport.NONE,
                null,
                null));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(
                new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.settings(PermissionAction.ASK));

    ToolGateway.PreflightResult result = gateway.preflight(request, SCOPE);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertEquals("Permission rules require approval", ask.reason());
    assertEquals(original, arguments);
    assertEquals(argumentsJson, JsonValues.write(arguments));
    assertEquals(argumentsJson, request.call().argumentsJson());
    assertEquals(original, JsonValues.readTree(request.call().argumentsJson()));
    assertEquals(
        hugeWorkdir, JsonValues.readTree(request.call().argumentsJson()).get("workdir").asText());
  }

  @Test
  void preflightIsSideEffectFreeAndKeepsFrozenRequestUntouched() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStager store =
        new ToolGatewayTestSupport.FakeResourceStager();
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog(tool);
    ToolExecutionGateway gateway = ToolGatewayTestSupport.gateway(catalog, transport, store);
    ToolInvocationRequest request = ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);
    gateway.preflight(request, SCOPE);
    assertTrue(transport.invocations.isEmpty());
    assertTrue(store.puts.isEmpty());
    assertTrue(tool.requests.isEmpty(), "preflight must never execute the tool");
    ToolGateway.Run run = ToolGatewayTestSupport.admitted(gateway, request);
    try {
      run.execute(partial -> {});
    } finally {
      run.close();
    }
    assertEquals(1, tool.requests.size());
    assertSame(request.call(), tool.requests.get(0).call());
    assertEquals(request.binding().descriptor(), tool.requests.get(0).descriptor());
  }

  /** 环境工具的审批理由同样固定。 */
  @Test
  void environmentToolAskUsesFixedReason() {
    EnvironmentId environmentId = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    ToolGateway.PreflightResult result =
        environmentPreflight(
            environmentId, "{\"path\":\"/repo/sub/src/Main.java\"}", PermissionAction.ASK);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertEquals("Permission rules require approval", ask.reason());
  }

  /** 没有 workdir 语义的调用不显示任何虚构默认目录。 */
  @Test
  void toolWithoutWorkdirDoesNotRenderFabricatedDefaultDirectory() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.settings(PermissionAction.ASK));
    ToolGateway.PreflightResult result =
        gateway.preflight(ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR), SCOPE);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertEquals("Permission rules require approval", ask.reason());
  }

  /** vfs: edit 的审批由 VFS 生成编辑预览：Ask 携带同一份审查事实。 */
  @Test
  void vfsEditApprovalCarriesTheVfsPreview() {
    ToolGatewayTestSupport.FakeVfsEditReviewSource source =
        new ToolGatewayTestSupport.FakeVfsEditReviewSource();
    source.review =
        new ToolEditReview(
            new ExpectedFile("vfs:/sessions/s/a.txt", "b".repeat(64)), "--- a\n+++ b\n-old\n+new");
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gatewayWith(
            ToolGatewayTestSupport.defaultCatalog(),
            new PermissionEvaluator(new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ASK)),
            source);

    ToolGateway.PreflightResult result =
        gateway.preflight(
            ToolGatewayTestSupport.vfsEditRequest(
                "call-vfs-edit",
                "vfs:/sessions/" + ToolGatewayTestSupport.ROOT_SESSION_ID + "/a.txt"),
            SCOPE);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertSame(source.review, ask.editReview());
    assertEquals("Permission rules require approval", ask.reason());
  }

  /** vfs: 编辑预览失败是确定性拒绝：既不弹审批，也不返回无审查的 Ask。 */
  @Test
  void vfsEditPreviewFailureIsDenied() {
    ToolGatewayTestSupport.FakeVfsEditReviewSource source =
        new ToolGatewayTestSupport.FakeVfsEditReviewSource();
    source.prepareFailure =
        new VfsEditRejectedException(
            ToolErrorGuidance.message(
                "edit old_string was not found in vfs:/sessions/s/a.txt",
                ToolErrorGuidance.ExecutionFact.NOT_EXECUTED,
                "Read the file and pass text that matches exactly"));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gatewayWith(
            ToolGatewayTestSupport.defaultCatalog(),
            new PermissionEvaluator(new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ASK)),
            source);

    ToolGateway.PreflightResult result =
        gateway.preflight(
            ToolGatewayTestSupport.vfsEditRequest(
                "call-vfs-edit",
                "vfs:/sessions/" + ToolGatewayTestSupport.ROOT_SESSION_ID + "/a.txt"),
            SCOPE);
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals(EditReviewPreflight.FAILED_KIND, deny.error().kind());
    assertTrue(deny.error().message().contains("old_string was not found"));
    assertTrue(deny.error().message().contains("The tool was not executed."));
  }

  /** 逐调用路由的本地 edit 也必须在审批前冻结真实远端审查。 */
  @Test
  void routedEnvironmentEditApprovalCarriesPreviewAndRejectsPreviewFailure() {
    EnvironmentId environmentId = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    ToolContribution edit = ToolGatewayTestSupport.defaultCatalog().findTool("edit").orElseThrow();
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall(
                "call-edit",
                "edit",
                "{\"path\":\"/repo/a.txt\",\"old_string\":\"old\",\"new_string\":\"new\"}"),
            new ToolBinding(
                edit.definition(),
                new ContributorBinding(
                    edit.id().contributorId().value(), edit.id().localName(), List.of()),
                EnvironmentSupport.ROUTED,
                environmentId,
                "test-env"));
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult =
        new ToolResult(
            "preview",
            List.of(
                new JsonResultContent(
                    "{\"path\":\"/repo/a.txt\",\"diff\":\"-old\\n+new\",\"sha256\":\""
                        + "a".repeat(64)
                        + "\"}")),
            false,
            "{}");
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.settings(PermissionAction.ASK));

    ToolGateway.Ask ask =
        assertInstanceOf(ToolGateway.Ask.class, gateway.preflight(request, SCOPE));
    assertEquals(new ExpectedFile("/repo/a.txt", "a".repeat(64)), ask.editReview().expectedFile());
    assertEquals("-old\n+new", ask.editReview().diff());
    assertEquals(
        EnvironmentCapabilityIds.FS_EDIT_PREVIEW,
        transport.invocations.get(0).request().descriptor().id());
    assertEquals(environmentId, transport.invocations.get(0).environmentId());

    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNAVAILABLE;
    ToolGateway.Deny deny =
        assertInstanceOf(ToolGateway.Deny.class, gateway.preflight(request, SCOPE));
    assertEquals("EDIT_REVIEW_FAILED", deny.error().kind());
    assertTrue(deny.error().message().contains("The tool was not executed."));
  }

  /** path 规则以 filesystem root 为坐标系：命中绝对路径对应规则，不同路径分支不命中。 */
  @Test
  void pathRulesAreGradedAgainstFilesystemRoot() {
    EnvironmentId environmentId = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    ToolSettings settings =
        new ToolSettings(
            Map.of(
                "*",
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("repo/sub/src/**", PermissionAction.DENY))));
    ToolGateway.PreflightResult result =
        environmentPreflight(environmentId, "{\"path\":\"/repo/sub/src/Main.java\"}", settings);
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("PERMISSION_DENIED", deny.error().kind());

    // 不同路径分支不命中对应规则，回落 wildcard。
    ToolGateway.PreflightResult outside =
        environmentPreflight(environmentId, "{\"path\":\"/elsewhere/src/Main.java\"}", settings);
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
            new ToolGatewayTestSupport.FakeResourceStager(),
            settings);
    ContributorBinding contributor =
        new ContributorBinding(
            contribution.id().contributorId().value(), contribution.id().localName(), List.of());
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "read", argumentsJson),
            new ToolBinding(
                contribution.definition(),
                contributor,
                EnvironmentSupport.ROUTED,
                environmentId,
                "test-env"));
    return gateway.preflight(request, SCOPE);
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
            new ToolGatewayTestSupport.FakeResourceStager(),
            settings);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", PREFLIGHT_DESCRIPTOR.name(), argumentsJson),
            new ToolBinding(
                hostDefinition(PREFLIGHT_DESCRIPTOR),
                new ContributorBinding("test", "host-tool", List.of()),
                EnvironmentSupport.NONE,
                null,
                null));
    return gateway.preflight(request, SCOPE);
  }

  @Test
  void preflightAcceptsDynamicMcpTool() {
    // 意图：验证动态 MCP 工具在通过 RuntimeToolCatalog 聚合后能够顺利通过 Gateway preflight
    McpServerRepository repo = mock(McpServerRepository.class);
    McpToolCatalog mcpCatalog = new McpToolCatalog(repo);

    McpServer server =
        McpServer.builder()
            .name("mcp_server")
            .discoveryStatus(McpDiscoveryStatus.AVAILABLE)
            .enabled(true)
            .version(1L)
            .url("http://localhost:8080/mcp")
            .headers(Map.of())
            .timeoutMillis(5000L)
            .build();

    McpTool mcpTool =
        McpTool.builder()
            .name("mcp_mcp_server_echo")
            .serverName("mcp_server")
            .sourceName("echo")
            .description("echo tool")
            .inputSchemaJson(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}")
            .build();

    when(repo.getTool("mcp_mcp_server_echo")).thenReturn(Optional.of(mcpTool));
    when(repo.getByName("mcp_server")).thenReturn(Optional.of(server));
    when(repo.listAllServers()).thenReturn(List.of(server));
    when(repo.listTools("mcp_server")).thenReturn(List.of(mcpTool));

    HarnessCatalog harnessCatalog = HarnessCatalog.from(List.of());
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            TestRuntimeToolCatalogSource.of(harnessCatalog, mcpCatalog),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            new ConcurrencyAdmission(Integer.MAX_VALUE));

    ToolContribution contribution = mcpCatalog.findTool("mcp_mcp_server_echo").orElseThrow();
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-mcp", "mcp_mcp_server_echo", "{}"),
            new ToolBinding(
                contribution.definition(),
                new ContributorBinding(
                    contribution.id().contributorId().value(),
                    contribution.id().localName(),
                    List.of()),
                EnvironmentSupport.NONE,
                null,
                null));

    ToolGateway.PreflightResult result = gateway.preflight(request, SCOPE);
    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  private static AgentToolDefinition hostDefinition(ToolDescriptor descriptor) {
    return new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE);
  }
}
