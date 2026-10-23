package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.testing.TestEnvironments;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 远程 Environment 工具执行 deadline 与收尾行为契约测试。
 *
 * <p>验证阻塞等待严格约束在单一绝对 deadline（{@code request.timeout + transportHandle.terminationGrace}）： 缺
 * terminal 时有界收敛为 unknown，grace 期间交付时带出真实输出，持续 partial 不重置或延期 deadline， 线程中断或 deadline 到期时调用 {@code
 * terminate(cause)} 而非单纯 cancel，正常调用在预算内交付恰好一次。
 */
class ToolExecutionGatewayRemoteDeadlineTest {

  private static final EnvironmentId ENV = TestEnvironments.environmentId("remote-deadline");
  private static final Duration CAPABILITY_TIMEOUT = Duration.ofMillis(60);
  private static final EnvironmentCapabilityDescriptor CAPABILITY =
      new EnvironmentCapabilityDescriptor(
          new EnvironmentCapabilityId("remote.test"),
          "1",
          new InputSchema("arguments", Map.of(), Set.of(), false),
          CAPABILITY_TIMEOUT);
  private static final ToolDescriptor TOOL_DESCRIPTOR =
      new ToolDescriptor(
          "remote-tool",
          "remote capability tool for deadline tests",
          "remote-tool",
          CAPABILITY.inputSchema(),
          ToolSideEffect.READ_ONLY,
          CAPABILITY_TIMEOUT);

  /**
   * 测试意图：远端丢失 terminal 帧时，阻塞等待严格受限于单一绝对 deadline（request.timeout +
   * transportHandle.terminationGrace）， 有界退出且结果收敛为 UNKNOWN，同时向句柄发送 TIMED_OUT 原因的 terminate。
   */
  @Test
  void missingTerminalTimesOutAtSingleDeadlineAndYieldsUnknown() throws Exception {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    Duration grace = Duration.ofMillis(40);
    transport.delegate.defaultTerminationGrace = grace;

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-missing"));
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      worker.join(3_000);
      assertFalse(
          worker.isAlive(), "worker must exit within bounded deadline without external interrupt");

      ToolGateway.Completion result = completion.get();
      assertNotNull(result, "completion must be delivered upon timeout");
      ToolGateway.Unknown unknown = assertInstanceOf(ToolGateway.Unknown.class, result);
      assertEquals(ToolExecutionGateway.REMOTE_UNCERTAIN_KIND, unknown.error().kind());
      assertTrue(
          unknown.error().message().contains("cannot be confirmed"),
          "deadline timeout outcome is uncertain and must not claim not executed");

      ToolGatewayTestSupport.FakeToolHandle handle = transport.delegate.returnedHandles.get(0);
      assertEquals(
          List.of(EnvironmentCapabilityTerminationCause.TIMED_OUT),
          handle.terminations(),
          "deadline timeout must terminate the handle with TIMED_OUT cause");
      assertEquals(1, handle.cancelCount());
    } finally {
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  /**
   * 测试意图：执行耗时超过 request.timeout，但在 terminationGrace 窗口期内远端完成收尾并交付终态结果时， Gateway
   * 阻塞等待该真实终态并正常返回其输出，不得触发 terminate。
   */
  @Test
  void terminalDeliveredWithinGraceYieldsTruthfulOutput() throws Exception {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    // 60ms timeout + 300ms grace = 360ms 绝对 deadline
    transport.delegate.defaultTerminationGrace = Duration.ofMillis(300);

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run = ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-grace"));
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      EnvironmentCapabilityExecutionListener listener =
          transport.delegate.invocations.get(0).listener();
      String capabilityCallId = transport.delegate.invocations.get(0).request().call().id();

      // 在 100ms 时交付（>60ms timeout 且 <360ms deadline）
      Thread.sleep(100);
      listener.onComplete(
          new EnvironmentCapabilityResult(
              capabilityCallId,
              List.of(new TextResultContent("truthful timeout process output")),
              false,
              "{\"outcome\":\"TIMED_OUT\"}"));

      worker.join(3_000);
      assertFalse(worker.isAlive(), "worker must complete after terminal delivery");

      ToolGateway.Completion result = completion.get();
      ToolGateway.Succeeded succeeded = assertInstanceOf(ToolGateway.Succeeded.class, result);
      assertEquals("call-grace", succeeded.success().result().toolCallId());
      assertEquals(
          "truthful timeout process output",
          ((TextResultContent) succeeded.success().result().contents().get(0)).text(),
          "grace 窗口内交付的真实输出必须原样保留并呈现");

      ToolGatewayTestSupport.FakeToolHandle handle = transport.delegate.returnedHandles.get(0);
      assertTrue(handle.terminations().isEmpty(), "grace 窗口内正常交付的终态不得触发 terminate");
      assertEquals(0, handle.cancelCount());
    } finally {
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  /** 测试意图：远端持续交付 partial 事件绝不能重置或延长单一绝对 deadline； 到期依然按原定绝对时间点发生超时并收敛为 UNKNOWN。 */
  @Test
  void continuousPartialsDoNotExtendSingleAbsoluteDeadline() throws Exception {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminationGrace = Duration.ZERO;

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-partial"));
    List<ToolResult> partials = new CopyOnWriteArrayList<>();
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partials::add)));

    AtomicBoolean streaming = new AtomicBoolean(true);
    Thread partialSender =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    if (transport.invoked.await(5, TimeUnit.SECONDS)) {
                      EnvironmentCapabilityExecutionListener listener =
                          transport.delegate.invocations.get(0).listener();
                      String capabilityCallId =
                          transport.delegate.invocations.get(0).request().call().id();
                      int index = 0;
                      while (streaming.get() && index < 20) {
                        Thread.sleep(15);
                        if (!streaming.get()) {
                          break;
                        }
                        listener.onPartial(
                            new EnvironmentCapabilityResult(
                                capabilityCallId,
                                List.of(new TextResultContent("chunk-" + index)),
                                false,
                                "{}"));
                        index++;
                      }
                    }
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                });

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      long startNanos = System.nanoTime();
      worker.join(2_000);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

      assertFalse(
          worker.isAlive(), "worker must exit without being delayed indefinitely by partials");
      // 60ms timeout，即使持续发 partial，也必须在短时间内（例如 250ms 内）超时退出，而非被 20*15ms=300ms 续期
      assertTrue(
          elapsedMillis < 250,
          "partial stream must not extend absolute deadline; elapsed=" + elapsedMillis + "ms");

      ToolGateway.Completion result = completion.get();
      ToolGateway.Unknown unknown = assertInstanceOf(ToolGateway.Unknown.class, result);
      assertEquals(ToolExecutionGateway.REMOTE_UNCERTAIN_KIND, unknown.error().kind());
      assertFalse(
          partials.isEmpty(), "at least one partial must have been observed before timeout");

      ToolGatewayTestSupport.FakeToolHandle handle = transport.delegate.returnedHandles.get(0);
      assertEquals(
          List.of(EnvironmentCapabilityTerminationCause.TIMED_OUT),
          handle.terminations(),
          "deadline 到期必须发送 TIMED_OUT 终止");
    } finally {
      streaming.set(false);
      partialSender.interrupt();
      partialSender.join(1_000);
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  /** 测试意图：工作线程阻塞等待被外部中断时，立即调用句柄的 terminate(CANCELLED)（非单纯 cancel）， 退出执行并收敛为 Cancelled 终态。 */
  @Test
  void interruptionInvokesTerminateWithCancelledCauseAndCleansUp() throws Exception {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    // 长超时，保证只有中断才会退出
    transport.delegate.defaultTerminationGrace = Duration.ofSeconds(10);

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-interrupt"));
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      worker.interrupt();
      worker.join(3_000);
      assertFalse(worker.isAlive(), "worker must exit immediately upon interrupt");

      ToolGateway.Completion result = completion.get();
      assertInstanceOf(ToolGateway.Cancelled.class, result);

      ToolGatewayTestSupport.FakeToolHandle handle = transport.delegate.returnedHandles.get(0);
      assertEquals(
          List.of(EnvironmentCapabilityTerminationCause.CANCELLED),
          handle.terminations(),
          "interruption must invoke terminate with CANCELLED cause");
      assertEquals(1, handle.cancelCount());
    } finally {
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  /** 测试意图：调用在 deadline 内正常完成时，交付恰好一次 Succeeded 终态， 且绝不调用句柄的 terminate 或 cancel。 */
  @Test
  void normalExecutionCompletesOnceWithoutTermination() throws Exception {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminationGrace = Duration.ofMillis(100);

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run = ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-normal"));
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      EnvironmentCapabilityExecutionListener listener =
          transport.delegate.invocations.get(0).listener();
      String capabilityCallId = transport.delegate.invocations.get(0).request().call().id();
      listener.onComplete(
          new EnvironmentCapabilityResult(
              capabilityCallId, List.of(new TextResultContent("normal success")), false, "{}"));

      worker.join(3_000);
      assertFalse(worker.isAlive(), "worker must complete on terminal delivery");

      ToolGateway.Completion result = completion.get();
      ToolGateway.Succeeded succeeded = assertInstanceOf(ToolGateway.Succeeded.class, result);
      assertEquals("call-normal", succeeded.success().result().toolCallId());
      assertEquals(
          "normal success",
          ((TextResultContent) succeeded.success().result().contents().get(0)).text());

      ToolGatewayTestSupport.FakeToolHandle handle = transport.delegate.returnedHandles.get(0);
      assertTrue(
          handle.terminations().isEmpty(),
          "normal successful completion must not trigger termination");
      assertEquals(0, handle.cancelCount());
    } finally {
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  private static ToolExecutionGateway gateway(HarnessCatalog catalog, LatchingTransport transport) {
    return new ToolExecutionGateway(
        TestRuntimeToolCatalogSource.of(catalog),
        ToolGatewayTestSupport.DEFAULT_CONTRIBUTOR_BRANCH_LOADER,
        transport,
        new PermissionEvaluator(new BashSurfaceAnalyzer()),
        new ToolGatewayTestSupport.FixedToolSettingsProvider(
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
        new ToolGatewayTestSupport.FakeResourceStager(),
        ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
        ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
        ToolGatewayTestSupport.TEST_CLOCK,
        new ConcurrencyAdmission(Integer.MAX_VALUE));
  }

  private static HarnessCatalog catalog() {
    EnvironmentCapabilityTool tool = new EnvironmentCapabilityTool(TOOL_DESCRIPTOR, CAPABILITY);
    return HarnessCatalog.from(
        List.of(
            HarnessContributor.of(
                new ContributorDescriptor(new ContributorId("remote"), "Remote", "1", Set.of()),
                registrar ->
                    registrar.registerTool("remote-tool", tool, ToolVisibility.SELECTABLE, 0))));
  }

  private static ToolInvocationRequest request(HarnessCatalog catalog, String callId) {
    ToolContribution contribution = catalog.findTool("remote-tool").orElseThrow();
    return new ToolInvocationRequest(
        new ToolCall(callId, "remote-tool", "{}"),
        new ToolBinding(
            contribution.definition(),
            new ContributorBinding(
                contribution.id().contributorId().value(),
                contribution.id().localName(),
                List.of()),
            EnvironmentSupport.REQUIRED,
            ENV,
            "remote-env"));
  }

  /** 记录 invoke 发生并委托 {@link ToolGatewayTestSupport.FakeTransport}。 */
  private static final class LatchingTransport implements EnvironmentCapabilityTransport {

    private final ToolGatewayTestSupport.FakeTransport delegate =
        new ToolGatewayTestSupport.FakeTransport();
    private final CountDownLatch invoked = new CountDownLatch(1);

    @Override
    public EnvironmentCapabilityExecutionHandle invoke(
        EnvironmentId environmentId,
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      EnvironmentCapabilityExecutionHandle handle =
          delegate.invoke(environmentId, request, listener);
      invoked.countDown();
      return handle;
    }
  }
}
