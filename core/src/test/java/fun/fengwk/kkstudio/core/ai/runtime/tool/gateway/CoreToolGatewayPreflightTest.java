package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link CoreToolGateway#preflight}：YOLO 与三个 permission 状态的映射，且不改写冻结 request。
 *
 * <p>使用虚构 PLATFORM tool {@code demo} + {@code path} 参数（避开 bash command 分析路径），permission 规则按 {@code
 * *} 通配全量生效，因此断言确定。
 */
class CoreToolGatewayPreflightTest {

  private static final ToolDescriptor DESCRIPTOR =
      ToolGatewayTestSupport.platformDescriptor("demo");
  private static final ToolDescriptor PREFLIGHT_DESCRIPTOR = preflightDescriptor();

  private static ToolDescriptor preflightDescriptor() {
    return new ToolDescriptor(
        "demo",
        "1",
        ToolType.PLATFORM,
        "description of demo",
        null,
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
    ToolGateway.PreflightResult result = preflight(PermissionAction.ALLOW, false);
    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void askWhenRulesAskWithBoundedPreviewReason() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.ASK, false);
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    // canonical 单行 reason 包含 tool 与 bounded arguments preview。
    assertTrue(ask.reason().contains("demo"), ask.reason());
    assertTrue(ask.reason().contains("\"path\":\"/tmp/x\""), ask.reason());
    assertEquals(ask.reason(), ask.reason().strip());
    assertTrue(ask.reason().length() <= 1024);
  }

  @Test
  void denyWhenRulesDenyWithStableKindAndMessage() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.DENY, false);
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("PERMISSION_DENIED", deny.error().kind());
    assertEquals("Tool permission was denied.", deny.error().message());
  }

  @Test
  void yoloOverridesDenyToAllow() {
    ToolGateway.PreflightResult result = preflight(PermissionAction.DENY, true);
    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void yoloReturnsAllowBeforeLoadingSettingsOrEvaluator() {
    // settings provider 与 evaluator 都抛异常：YOLO 路径在加载它们之前直接 Allow。
    CoreToolGateway gateway =
        new CoreToolGateway(
            ToolGatewayTestSupport.factories(),
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
            () -> {
              throw new IllegalStateException("settings store unavailable");
            },
            new ToolGatewayTestSupport.FakeResourceStore(),
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.CONFIG);
    ToolGateway.PreflightResult result =
        gateway.preflight(ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR), true);
    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void askReasonLongerThan1024IsTruncatedAtCodePointBoundary() {
    // workdir 内大量 surrogate pair（emoji）撑过 1024 字符上限；1 字符 tool 名使 emoji 段从偶数下标开始，
    // 截断点必然落在代理对中间——截断必须回退到码点边界，绝不劈开代理对、绝不超长。
    Path hugeWorkdir = Path.of("/w/" + "\uD83D\uDE00".repeat(520) + "/deep");
    ToolGateway.PreflightResult result =
        preflightTruncation(PermissionAction.ASK, false, hugeWorkdir, Path.of("/env-root"));
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
    ToolFactories factories = ToolGatewayTestSupport.factories(tool);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CoreToolGateway gateway =
          ToolGatewayTestSupport.gateway(factories, transport, store, executor);
      ToolInvocationRequest request = ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR);
      gateway.preflight(request, false);
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

  private static ToolGateway.PreflightResult preflight(PermissionAction action, boolean yolo) {
    return preflight(
        action, yolo, ToolGatewayTestSupport.WORKDIR, ToolGatewayTestSupport.ENVIRONMENT_ROOT);
  }

  private static ToolGateway.PreflightResult preflight(
      PermissionAction action, boolean yolo, Path workdir, Path environmentRoot) {
    CoreToolGateway gateway =
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
            new ToolBinding(PREFLIGHT_DESCRIPTOR, ToolType.PLATFORM, null));
    return gateway.preflight(request, yolo);
  }

  /** 单字符 tool 名 + path 参数的 preflight fixture：emoji workdir 从偶数下标开始，截断点劈开代理对。 */
  private static ToolDescriptor truncationDescriptor() {
    return new ToolDescriptor(
        "x",
        "1",
        ToolType.PLATFORM,
        "description of x",
        null,
        new ToolParamsSchema(
            "arguments",
            Map.of("path", new ToolStringSchema("Target path")),
            Set.of("path"),
            false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  private static ToolGateway.PreflightResult preflightTruncation(
      PermissionAction action, boolean yolo, Path workdir, Path environmentRoot) {
    ToolDescriptor descriptor = truncationDescriptor();
    CoreToolGateway gateway =
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
            new ToolBinding(descriptor, ToolType.PLATFORM, null));
    return gateway.preflight(request, yolo);
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
