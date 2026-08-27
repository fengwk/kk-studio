package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.BaseToolIds;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link PlatformToolGateway#preflight}：三个 permission 状态的映射，且不改写冻结 request。
 *
 * <p>使用虚构 HOST tool {@code demo} + {@code path} 参数（避开 bash command 分析路径），permission 规则按 {@code *}
 * 通配全量生效，因此断言确定。
 */
class PlatformToolGatewayPreflightTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");
  private static final ToolDescriptor PREFLIGHT_DESCRIPTOR = preflightDescriptor();

  private static ToolDescriptor preflightDescriptor() {
    return new ToolDescriptor(
        "demo",
        "1",
        "description of demo",
        "demo",
        new ToolParamsSchema(
            "arguments",
            Map.of("path", new ToolStringSchema("Target path")),
            Set.of("path"),
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
    // canonical 单行 reason 包含 tool 与 bounded arguments preview。
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
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            settings);

    ToolGateway.Deny deny =
        assertInstanceOf(
            ToolGateway.Deny.class,
            gateway.preflight(ToolGatewayTestSupport.hostRequest("call-1", PREFLIGHT_DESCRIPTOR)));

    // registry 中的 frozen entry 将 model-visible name demo 恢复为 test.host-tool；不能误用 name 规则。
    assertEquals(PlatformToolGateway.PERMISSION_DENIED_KIND, deny.error().kind());
    assertEquals("Tool permission was denied.", deny.error().message());
  }

  @Test
  void unknownAndMismatchedDescriptorsAreDeniedBeforePermissionEvaluation() {
    PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
    PlatformToolGateway gateway =
        new PlatformToolGateway(
            ToolGatewayTestSupport.factories(
                new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR)),
            ToolGatewayTestSupport.EMPTY_PLUGIN_CATALOG,
            ToolGatewayTestSupport.FAILING_PLUGIN_BRANCH_LOADER,
            new ToolGatewayTestSupport.FakeTransport(),
            evaluator,
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            new ToolGatewayTestSupport.FakeResourceStore(),
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.BUSY_RETRY_DELAY,
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
                            new AgentToolId("test.missing"),
                            unknown,
                            ToolVisibility.SELECTABLE,
                            AgentToolBackend.HOST),
                        null,
                        null))));
    assertEquals(PlatformToolGateway.TOOL_NOT_FOUND_KIND, unknownDeny.error().kind());
    assertEquals(
        "Frozen tool definition test.missing is not registered.", unknownDeny.error().message());

    ToolDescriptor mismatched = ToolGatewayTestSupport.hostDescriptor("demo");
    ToolGateway.Deny mismatchDeny =
        assertInstanceOf(
            ToolGateway.Deny.class,
            gateway.preflight(
                new ToolInvocationRequest(
                    new ToolCall("mismatch", "demo", "{}"),
                    new ToolBinding(hostDefinition(mismatched), null, null))));
    // 两个请求都在 evaluator 前收敛；mock 没有任何交互，证明未知/漂移 descriptor 不会产生评估副作用。
    assertEquals(PlatformToolGateway.TOOL_DEFINITION_MISMATCH_KIND, mismatchDeny.error().kind());
    assertEquals(
        "Frozen tool definition test.host-tool does not match its catalog definition.",
        mismatchDeny.error().message());
    verifyNoInteractions(evaluator);
  }

  @Test
  void askReasonLongerThan1024IsTruncatedAtCodePointBoundary() {
    // workdir 内大量 surrogate pair（emoji）撑过 1024 字符上限；1 字符 tool 名使 emoji 段从偶数下标开始，
    // 截断点必然落在代理对中间——截断必须回退到码点边界，绝不劈开代理对、绝不超长。
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
    var factories = ToolGatewayTestSupport.factories(tool);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      PlatformToolGateway gateway =
          ToolGatewayTestSupport.gateway(factories, transport, store, executor);
      ToolInvocationRequest request = ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);
      gateway.preflight(request);
      // preflight 不触碰 transport / factories / store；start 使用同一冻结 request 实例执行。
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
    // ENVIRONMENT_CAPABILITY tool 的权限上下文必须体现冻结 binding 的 workspace：Ask reason 的默认 workdir 是
    // environmentRoot 下的 canonical workspacePath，而不是 server 默认 workdir。
    EnvironmentBinding binding = new EnvironmentBinding(new EnvironmentName("env-1"), "repo/sub");
    ToolGateway.PreflightResult result =
        environmentPreflight(binding, "{\"path\":\"src/Main.java\"}", PermissionAction.ASK);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertTrue(ask.reason().contains("/environment-root/repo/sub (default)"), ask.reason());
  }

  @Test
  void environmentWorkspaceIsTheOnlyPathRuleBase() {
    // path 只相对冻结 binding 的 effective workdir（environmentRoot 下的 canonical workspacePath）解析，
    // environmentRoot 不再作为 pattern 坐标：src/** 命中 DENY，而旧的环境相对坐标 repo/sub/** 不再命中。
    EnvironmentBinding binding = new EnvironmentBinding(new EnvironmentName("env-1"), "repo/sub");
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

    ToolSettings environmentRelativeAlias =
        new ToolSettings(
            Map.of(
                "*",
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("repo/sub/**", PermissionAction.DENY))),
            false);
    ToolGateway.PreflightResult aliasResult =
        environmentPreflight(binding, "{\"path\":\"src/Main.java\"}", environmentRelativeAlias);
    assertInstanceOf(ToolGateway.Ask.class, aliasResult);
  }

  @Test
  void platformPreflightKeepsServerDefaultWorkdir() {
    // HOST 行为不变：权限路径上下文仍使用 server 默认 workdir。
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
    ToolDescriptor descriptor =
        EnvironmentToolCatalog.require(BaseToolIds.READ).definition().descriptor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            settings,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "read", argumentsJson),
            new ToolBinding(environmentDefinition(descriptor), environment, null));
    return gateway.preflight(request);
  }

  private static ToolGateway.PreflightResult preflight(
      PermissionAction action, Path workdir, Path environmentRoot) {
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(
                new ToolGatewayTestSupport.FakeTool(PREFLIGHT_DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.settings(action),
            workdir,
            environmentRoot);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "demo", "{\"path\":\"/tmp/x\"}"),
            new ToolBinding(hostDefinition(PREFLIGHT_DESCRIPTOR), null, null));
    return gateway.preflight(request);
  }

  /** 单字符 tool 名 + path 参数的 preflight fixture：emoji workdir 从偶数下标开始，截断点劈开代理对。 */
  private static ToolDescriptor truncationDescriptor() {
    return new ToolDescriptor(
        "x",
        "1",
        "description of x",
        "x",
        new ToolParamsSchema(
            "arguments",
            Map.of("path", new ToolStringSchema("Target path")),
            Set.of("path"),
            false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  private static ToolGateway.PreflightResult preflightTruncation(
      PermissionAction action, Path workdir, Path environmentRoot) {
    ToolDescriptor descriptor = truncationDescriptor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(new ToolGatewayTestSupport.FakeTool(descriptor)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.settings(action),
            workdir,
            environmentRoot);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "x", "{\"path\":\"/tmp/x\"}"),
            new ToolBinding(hostDefinition(descriptor), null, null));
    return gateway.preflight(request);
  }

  private static AgentToolDefinition hostDefinition(ToolDescriptor descriptor) {
    return new AgentToolDefinition(
        ToolGatewayTestSupport.TEST_TOOL_ID,
        descriptor,
        ToolVisibility.SELECTABLE,
        AgentToolBackend.HOST);
  }

  private static AgentToolDefinition environmentDefinition(ToolDescriptor descriptor) {
    return EnvironmentToolCatalog.entries().stream()
        .filter(entry -> entry.definition().descriptor().equals(descriptor))
        .findFirst()
        .orElseThrow()
        .definition();
  }

  /** 判断字符串是否包含未配对 surrogate（被劈开的代理对）。 */
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
