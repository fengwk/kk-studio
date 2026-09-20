package fun.fengwk.kkstudio.harness.builtin.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** EnvironmentCapabilityTool 的委托、环境绑定缺失防护与构造一致性校验测试。 */
class EnvironmentCapabilityToolTest {

  private static final EnvironmentCapabilityDescriptor FS_READ =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
  private static final EnvironmentCapabilityDescriptor PROCESS_EXEC =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC);

  /** 构造时必须保证 descriptor 的 schema 与 defaultTimeout 与 capability descriptor 完全一致。 */
  @Test
  void constructorRejectsMismatchedSchemaOrDefaultTimeout() {
    ToolDescriptor validDescriptor =
        new ToolDescriptor(
            "read",
            "read file",
            "read",
            FS_READ.inputSchema(),
            ToolSideEffect.READ_ONLY,
            FS_READ.defaultTimeout());

    EnvironmentCapabilityTool tool = new EnvironmentCapabilityTool(validDescriptor, FS_READ);
    assertEquals(validDescriptor, tool.descriptor());
    assertEquals(FS_READ, tool.capability());
    assertEquals(ToolRequirements.environment(), tool.requirements());

    // Schema mismatch
    ToolDescriptor badSchema =
        new ToolDescriptor(
            "read",
            "read file",
            "read",
            new InputSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            FS_READ.defaultTimeout());
    assertThrows(
        IllegalArgumentException.class, () -> new EnvironmentCapabilityTool(badSchema, FS_READ));

    // defaultTimeout mismatch
    ToolDescriptor badTimeout =
        new ToolDescriptor(
            "read",
            "read file",
            "read",
            FS_READ.inputSchema(),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(99));
    assertThrows(
        IllegalArgumentException.class, () -> new EnvironmentCapabilityTool(badTimeout, FS_READ));
  }

  /** 执行上下文缺失环境绑定时，安全返回错误结果而不是抛出未捕获异常。 */
  @Test
  void executeWithoutBoundEnvironmentReturnsErrorResult() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "read",
            "read file",
            "read",
            FS_READ.inputSchema(),
            ToolSideEffect.READ_ONLY,
            FS_READ.defaultTimeout());
    EnvironmentCapabilityTool tool = new EnvironmentCapabilityTool(descriptor, FS_READ);

    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();
    ToolExecutionListener listener =
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            outcomeRef.set(outcome);
          }

          @Override
          public void onError(Throwable error) {}
        };

    ToolExecutionRequest requestWithoutContext =
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call-1", "read", "{\"workdir\":\"/srv/repo\",\"path\":\"demo.txt\"}"),
            Duration.ZERO);

    ToolExecutionHandle handle = tool.execute(requestWithoutContext, listener);
    assertNotNull(handle);
    assertFalse(handle.isCancelled());
    assertNotNull(outcomeRef.get());
    assertTrue(outcomeRef.get().result().error());
    assertEquals(
        "No environment bound in execution context",
        ((TextResultContent) outcomeRef.get().result().contents().get(0)).text());
  }

  /** 显式 timeout_seconds 严格覆盖 capability 默认超时：更短与更长都必须原样生效，不做 min clamp。 */
  @Test
  void resolveTimeoutUsesExplicitArgumentOrCapabilityDefault() {
    EnvironmentCapabilityTool bash = bashTool();

    assertEquals(Duration.ofMinutes(5), bash.descriptor().defaultTimeout());
    // 缺省：使用 definition（capability）默认值
    assertEquals(
        Duration.ofMinutes(5),
        bash.resolveTimeout(call("bash", "{\"command\":\"true\",\"workdir\":\"/srv/repo\"}")));
    // 显式更短
    assertEquals(
        Duration.ofSeconds(7),
        bash.resolveTimeout(
            call(
                "bash", "{\"command\":\"true\",\"workdir\":\"/srv/repo\",\"timeout_seconds\":7}")));
    // 显式更长（7200 秒）：必须原样返回，而不是被默认值截断
    assertEquals(
        Duration.ofSeconds(7200),
        bash.resolveTimeout(
            call(
                "bash",
                "{\"command\":\"true\",\"workdir\":\"/srv/repo\",\"timeout_seconds\":7200}")));
  }

  /** schema 声明 timeout_seconds 必须是正数：0 与负数都不是合法覆盖，也不是「无 deadline」，必须 fail closed。 */
  @Test
  void resolveTimeoutRejectsNonPositiveExplicitValue() {
    EnvironmentCapabilityTool bash = bashTool();

    IllegalArgumentException zero =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                bash.resolveTimeout(
                    call(
                        "bash",
                        "{\"command\":\"true\",\"workdir\":\"/srv/repo\",\"timeout_seconds\":0}")));
    assertTrue(zero.getMessage().contains("must be positive"), zero.getMessage());

    IllegalArgumentException negative =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                bash.resolveTimeout(
                    call(
                        "bash",
                        "{\"command\":\"true\",\"workdir\":\"/srv/repo\",\"timeout_seconds\":-5}")));
    assertTrue(negative.getMessage().contains("must be positive"), negative.getMessage());
  }

  /** 未声明 timeout 契约的能力（fs.read）忽略任意同名 arguments，只返回 definition 默认值。 */
  @Test
  void resolveTimeoutIgnoresUnknownArgumentsForCapabilitiesWithoutTimeoutContract() {
    EnvironmentCapabilityTool read =
        new EnvironmentCapabilityTool(
            new ToolDescriptor(
                "read",
                "read file",
                "read",
                FS_READ.inputSchema(),
                ToolSideEffect.READ_ONLY,
                FS_READ.defaultTimeout()),
            FS_READ);

    assertEquals(
        Duration.ofMinutes(1),
        read.resolveTimeout(call("read", "{\"workdir\":\"/srv/repo\",\"path\":\"demo.txt\"}")));
  }

  /** 以 capability schema 构造 bash Tool，用于验证 arguments 级超时解析。 */
  private static EnvironmentCapabilityTool bashTool() {
    return new EnvironmentCapabilityTool(
        new ToolDescriptor(
            "bash",
            "run command",
            "bash",
            PROCESS_EXEC.inputSchema(),
            ToolSideEffect.NON_IDEMPOTENT,
            PROCESS_EXEC.defaultTimeout()),
        PROCESS_EXEC);
  }

  /** 未归一化的 canonical call；resolveTimeout 期望输入已归一化参数。 */
  private static ToolCall call(String toolName, String argumentsJson) {
    return new ToolCall("call-timeout", toolName, argumentsJson);
  }

  /** 正常执行时委托至 BoundEnvironment 的 execute 方法并透传句柄。 */
  @Test
  void executeDelegatesToBoundEnvironment() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "read",
            "read file",
            "read",
            FS_READ.inputSchema(),
            ToolSideEffect.READ_ONLY,
            FS_READ.defaultTimeout());
    EnvironmentCapabilityTool tool = new EnvironmentCapabilityTool(descriptor, FS_READ);

    BoundEnvironment boundEnv = mock(BoundEnvironment.class);
    ToolExecutionHandle mockHandle = mock(ToolExecutionHandle.class);
    ToolExecutionListener listener = mock(ToolExecutionListener.class);

    BranchView branchView = mock(BranchView.class);
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), Instant.now(), branchView, Optional.of(boundEnv));

    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("call-2", "read", "{\"workdir\":\"/srv/repo\",\"path\":\"demo.txt\"}"),
            Duration.ZERO,
            context);

    when(boundEnv.execute(eq(FS_READ), eq(request), eq(listener))).thenReturn(mockHandle);

    ToolExecutionHandle returnedHandle = tool.execute(request, listener);
    assertEquals(mockHandle, returnedHandle);
    verify(boundEnv).execute(FS_READ, request, listener);
  }
}
