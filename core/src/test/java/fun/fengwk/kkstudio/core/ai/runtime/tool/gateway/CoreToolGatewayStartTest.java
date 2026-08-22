package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link CoreToolGateway#start}：PLATFORM / ENVIRONMENT 路由、admission 分类与同步回调门控。
 *
 * <p>ENVIRONMENT 断言只依赖冻结 {@code environmentName}（display name 完全不参与）；PLATFORM 断言精确的 {@link
 * ToolExecutionRequest}（descriptor / 冻结 call / Duration.ZERO / 精确 ToolExecutionContext）。
 */
class CoreToolGatewayStartTest {

  private static final ToolDescriptor DESCRIPTOR =
      ToolGatewayTestSupport.platformDescriptor("demo");

  @Test
  void platformRouteExecutesWithExactRequestAndContext() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ToolInvocationRequest request = ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CoreToolGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.factories(tool), transport, store, executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(request),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
      // 两阶段激活：activate 打开回调 gate 后 executor 任务才运行 Tool。
      startedResult.handle().activate();
      awaitSize(tool.requests, 1);
      ToolExecutionRequest executed = tool.requests.get(0);
      assertEquals(DESCRIPTOR, executed.descriptor());
      assertSame(request.call(), executed.call());
      assertEquals(Duration.ZERO, executed.timeout());
      assertEquals(
          new ToolExecutionContext(
              ToolGatewayTestSupport.INVOCATION_ID, ToolGatewayTestSupport.THREAD_ID),
          executed.context());
      // PLATFORM 绝不触碰 remote transport。
      assertTrue(transport.invocations.isEmpty());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void environmentRouteUsesFrozenEnvironmentNameOnlyAndSameDisplayNamesAreIrrelevant() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CoreToolGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.factories(), transport, store, executor);
      // 两个不同的 canonical 环境（display name 概念完全不进入 adapter 路由）。
      ToolInvocationRequest requestA =
          ToolGatewayTestSupport.environmentRequest("call-a", ToolGatewayTestSupport.ENV_A);
      ToolInvocationRequest requestB =
          ToolGatewayTestSupport.environmentRequest("call-b", ToolGatewayTestSupport.ENV_B);
      ToolGateway.StartResult startedA =
          gateway.start(
              ToolGatewayTestSupport.execution(requestA),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.StartResult startedB =
          gateway.start(
              ToolGatewayTestSupport.execution(requestB),
              new ToolGatewayTestSupport.RecordingListener());
      assertInstanceOf(ToolGateway.Started.class, startedA);
      assertInstanceOf(ToolGateway.Started.class, startedB);
      awaitSize(transport.invocations, 2);
      assertEquals(ToolGatewayTestSupport.ENV_A, transport.invocations.get(0).environment());
      assertEquals(ToolGatewayTestSupport.ENV_B, transport.invocations.get(1).environment());
      // 冻结 call 原样发送，携带与 PLATFORM 相同的 durable 上下文（daemon gateway 需要 invocationId）。
      assertSame(requestA.call(), transport.invocations.get(0).request().call());
      assertEquals(
          new ToolExecutionContext(
              ToolGatewayTestSupport.INVOCATION_ID, ToolGatewayTestSupport.THREAD_ID),
          transport.invocations.get(0).request().context());
      assertEquals(
          EnvironmentToolCatalog.require("bash"),
          transport.invocations.get(0).request().descriptor());
      assertEquals(Duration.ZERO, transport.invocations.get(0).request().timeout());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void missingPlatformToolIsRejected() {
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_NOT_FOUND", rejected.error().kind());
  }

  @Test
  void platformDescriptorMismatchIsRejected() {
    ToolDescriptor drifted =
        new ToolDescriptor(
            DESCRIPTOR.name(),
            DESCRIPTOR.version(),
            ToolType.PLATFORM,
            "drifted description",
            DESCRIPTOR.rendererKey(),
            new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(new ToolGatewayTestSupport.FakeTool(DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", drifted)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DESCRIPTOR_MISMATCH", rejected.error().kind());
  }

  @Test
  void missingEnvironmentCapabilityIsRejected() {
    ToolDescriptor unknown =
        new ToolDescriptor(
            "no_such_daemon_tool",
            "1",
            ToolType.ENVIRONMENT,
            "not in the daemon catalog",
            "no_such_daemon_tool",
            new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "no_such_daemon_tool", "{}"),
            new ToolBinding(unknown, ToolType.ENVIRONMENT, ToolGatewayTestSupport.ENV_A));
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(request),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_NOT_FOUND", rejected.error().kind());
  }

  @Test
  void environmentCapabilityMismatchIsRejected() {
    ToolDescriptor driftedBash =
        new ToolDescriptor(
            "bash",
            "1",
            ToolType.ENVIRONMENT,
            "drifted bash description",
            "bash",
            new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "bash", "{}"),
            new ToolBinding(driftedBash, ToolType.ENVIRONMENT, ToolGatewayTestSupport.ENV_A));
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(request),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DESCRIPTOR_MISMATCH", rejected.error().kind());
  }

  @Test
  void environmentToolWithoutRouteIsDeterministicRejectionWithoutTransport() {
    // 冻结 binding 的 route 为 null（分支最新 settings 未选中/已清空）：发送前确定性拒绝，
    // transport 绝不能收到 null route 调用（否则会变成不确定结果）。
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", null)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("UNAVAILABLE", rejected.error().kind());
    assertEquals(0, transport.invocations.size());
  }

  @Test
  void environmentUnavailableIsDeterministicRejectionVisibleToModel() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNAVAILABLE;
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener());
    // 发送前目标不可用（路由缺失/未 READY/心跳过期）是确定性拒绝：durable、model-visible，
    // 让 turn 正常继续收敛，而不是无限重试。
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("UNAVAILABLE", rejected.error().kind());
  }

  /** 同 Environment 的瞬时 active 槽位冲突必须返回精确配置延迟的 Busy，不创建 durable error。 */
  @Test
  void environmentBusyUsesExactConfiguredRetryDelay() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_BUSY;
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());

    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener());

    ToolGateway.Busy busy = assertInstanceOf(ToolGateway.Busy.class, result);
    assertEquals(ToolGatewayTestSupport.BUSY_RETRY_DELAY.get(), busy.retryAfter());
  }

  @Test
  void environmentUncertainSendIsIndeterminate() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNCERTAIN;
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Indeterminate indeterminate =
        assertInstanceOf(ToolGateway.Indeterminate.class, result);
    assertEquals("REMOTE_UNCERTAIN", indeterminate.error().kind());
  }

  @Test
  void environmentInvalidRequestIsRejected() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_INVALID;
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("INVALID_REQUEST", rejected.error().kind());
  }

  @Test
  void environmentUnexpectedTransportFailureIsIndeterminate() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_GENERIC;
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener());
    assertInstanceOf(ToolGateway.Indeterminate.class, result);
  }

  @Test
  void uncertainInvokeCancelsBridgeDroppingLateTransportCallbacks() throws Exception {
    assertIndeterminateClosesCallbackBridge(
        ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNCERTAIN, "REMOTE_UNCERTAIN");
  }

  @Test
  void genericInvokeFailureCancelsBridgeDroppingLateTransportCallbacks() throws Exception {
    assertIndeterminateClosesCallbackBridge(
        ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_GENERIC, "EXECUTION_FAILED");
  }

  @Test
  void localExecutorOverloadIsOverloadedWithWholeMillisecondDelay() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    executor.reject = true;
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(new ToolGatewayTestSupport.FakeTool(DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Overloaded overloaded = assertInstanceOf(ToolGateway.Overloaded.class, result);
    assertEquals(ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY.get(), overloaded.retryAfter());
    assertEquals(overloaded.retryAfter().toMillis(), overloaded.retryAfter().toNanos() / 1_000_000);
  }

  @Test
  void syncToolCallbackIsGatedUntilStartCommitted() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    // adversarial Tool：在 execute 内同步回调 terminal。
    tool.handler =
        (request, listener) ->
            listener.onComplete(ToolGatewayTestSupport.result(request.call().id(), "done"));
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // start 已返回但 gate 未打开：executor 任务仍在等待 release，任何回调都没有发生（门控生效）。
    assertTrue(listener.events.isEmpty());
    startedResult.handle().activate();
    executor.runAll();
    // activate 释放任务：同步 onComplete 在 gate 打开后串行投递。
    listener.awaitCount(1);
    assertEquals(
        "call-1",
        ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0))
            .result()
            .toolCallId());
  }

  @Test
  void environmentSyncCallbackIsBufferedAndReplayedOnActivation() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "sync");
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    int tasksBeforeStart = executor.tasks.size();
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // invoke 内的同步 onComplete 只进缓冲：start 返回时还没有任何回调，也没有任何 executor 任务被提交
    // （tasks 只含构造时 inline-executor 探测任务）。
    assertTrue(listener.events.isEmpty());
    assertEquals(
        tasksBeforeStart,
        executor.tasks.size(),
        "environment path must not submit any replay task");
    // 两阶段激活：activate 直接打开 gate 并串行重放缓冲（绝不提交独立重放任务）。
    startedResult.handle().activate();
    listener.awaitCount(1);
    assertEquals(
        "call-1",
        ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0))
            .result()
            .toolCallId());
  }

  @Test
  void factoryCreatingMismatchedToolIsRejectedAsInvalidRequest() {
    ToolDescriptor drifted = platformDriftedDescriptor("2");
    ToolFactories factories =
        new ToolFactories(
            List.of(
                new ToolFactory() {
                  @Override
                  public ToolDescriptor descriptor() {
                    return DESCRIPTOR;
                  }

                  @Override
                  public Tool create() {
                    return new ToolGatewayTestSupport.FakeTool(drifted);
                  }
                }));
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            factories,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    // create() 产物与注册 key 不匹配：find 抛确定性 IAE，映射为 Rejected(INVALID_REQUEST)。
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("INVALID_REQUEST", rejected.error().kind());
  }

  @Test
  void platformExecutorAmbiguousSubmissionIsIndeterminate() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    executor.executeFailure = new IllegalStateException("executor broken");
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(new ToolGatewayTestSupport.FakeTool(DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    // 提交抛出非 RejectedExecutionException：可能已启动，收敛为 Indeterminate 且绝不抛。
    ToolGateway.Indeterminate indeterminate =
        assertInstanceOf(ToolGateway.Indeterminate.class, result);
    assertEquals("EXECUTION_FAILED", indeterminate.error().kind());
    assertEquals("executor broken", indeterminate.error().message());
  }

  @Test
  void environmentActivationNeverSubmitsAReplayTask() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    executor.reject = true;
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "sync");
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    // invoke 已成功、executor 拒绝一切提交：ENVIRONMENT 路径不依赖任何重放任务，activate 直接打开 gate 并重放。
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(listener.events.isEmpty());
    startedResult.handle().activate();
    listener.awaitCount(1);
    assertEquals(
        "call-1",
        ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0))
            .result()
            .toolCallId());
  }

  @Test
  void cancelBeforeEnvironmentActivationDropsBufferedCallbacks() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "sync");
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // cancel-before-activate：transport handle 被取消；之后 activate no-op，缓冲回调永不投递。
    startedResult.handle().cancel();
    startedResult.handle().activate();
    executor.runAll();
    assertTrue(listener.events.isEmpty());
    assertTrue(transport.returnedHandles.get(0).isCancelled());
  }

  @Test
  void nullEnvironmentTransportHandleIsPostAcceptanceUnknown() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.returnNullHandle = true;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // invoke 返回 null handle：已接受的执行结果无法确认，activate 时恰好一次 UNKNOWN；无 transport handle 可取消。
    assertTrue(transport.returnedHandles.isEmpty());
    startedResult.handle().activate();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
  }

  @Test
  void synchronousTerminalWinsOverNullEnvironmentTransportHandle() {
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "sync");
    transport.returnNullHandle = true;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // 同步 terminal 在 null-handle 失败之前入队：FIFO/terminal-once 下 terminal 获胜，null-handle 信号被丢弃。
    startedResult.handle().activate();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertEquals("call-1", succeeded.result().toolCallId());
  }

  private static ToolDescriptor platformDriftedDescriptor(String version) {
    return new ToolDescriptor(
        DESCRIPTOR.name(),
        version,
        ToolType.PLATFORM,
        "drifted description",
        DESCRIPTOR.rendererKey(),
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  private static void awaitSize(List<?> list, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (list.size() < expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, list.size());
  }

  private static void assertIndeterminateClosesCallbackBridge(
      ToolGatewayTestSupport.FakeTransport.InvokeAction action, String expectedKind)
      throws Exception {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = action;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    CoreToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Indeterminate indeterminate =
        assertInstanceOf(ToolGateway.Indeterminate.class, result);
    assertEquals(expectedKind, indeterminate.error().kind());
    // Indeterminate 后桥必须已取消：迟到的 transport 回调被丢弃而不是永远缓冲，任何回调都绝不触达 listener。
    ToolExecutionListener bridge = transport.invocations.get(0).listener();
    bridge.onPartial(ToolGatewayTestSupport.result("call-1", "late"));
    bridge.onComplete(ToolGatewayTestSupport.result("call-1", "late done"));
    bridge.onError(new IllegalStateException("late"));
    assertTrue(bridgeIsCancelled(bridge), "Indeterminate must close the callback bridge");
    assertEquals(0, bridgeQueueSize(bridge), "late callbacks must be dropped, not buffered");
    assertTrue(listener.events.isEmpty(), "no callback may ever reach the listener");
  }

  /** 白盒断言桥内部已 cancel（transport 持有的桥引用不可从外部触达）。 */
  private static boolean bridgeIsCancelled(ToolExecutionListener bridge) throws Exception {
    Field field = bridge.getClass().getDeclaredField("cancelled");
    field.setAccessible(true);
    return field.getBoolean(bridge);
  }

  /** 白盒断言桥缓冲为空（迟到回调被丢弃而非永远缓冲）。 */
  private static int bridgeQueueSize(ToolExecutionListener bridge) throws Exception {
    Field field = bridge.getClass().getDeclaredField("queue");
    field.setAccessible(true);
    return ((ArrayDeque<?>) field.get(bridge)).size();
  }
}
