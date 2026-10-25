package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.IntegerSchema;
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
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
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
 * <p>验证阻塞等待严格约束在单一绝对 deadline（{@code request.timeout + transportHandle.terminalDeliveryGrace}）：缺
 * terminal 时有界收敛为 unknown，恢复窗内交付时带出真实输出，持续 partial 不重置或延期 deadline，线程中断或 deadline 到期时调用 {@code
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
  private static final InputSchema TIMEOUT_SCHEMA =
      new InputSchema(
          "arguments",
          Map.of("timeout_seconds", new IntegerSchema("execution budget in seconds")),
          Set.of(),
          false);

  /** 缺失 terminal 时在单一 deadline 收敛为 UNKNOWN，并以 TIMED_OUT 终止句柄。 */
  @Test
  void missingTerminalTimesOutAtSingleDeadlineAndYieldsUnknown() throws Exception {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    Duration grace = Duration.ofMillis(40);
    transport.delegate.defaultTerminalDeliveryGrace = grace;

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();

    try (ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway, request(catalog, "call-missing"), remoteExecutions)) {
      Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));
      try (AutoCloseable workerExit =
          () -> {
            worker.interrupt();
            assertTrue(worker.join(Duration.ofSeconds(5)), "deadline worker must exit in cleanup");
          }) {
        assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
        worker.join(Duration.ofSeconds(3));
        assertFalse(
            worker.isAlive(),
            "worker must exit within bounded deadline without external interrupt");

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
        assertTrue(
            remoteExecutions.notStartedConfirmed.isEmpty(),
            "timeout after send is not a not-started confirmation");
        assertTrue(remoteExecutions.exitConfirmed.isEmpty(), "timeout is not a positive exit ACK");
      }
    }
  }

  /** 执行预算耗尽后，恢复窗内交付的真实终态仍应保留，不触发 terminate。 */
  @Test
  void terminalDeliveredWithinGraceYieldsTruthfulOutput() throws Exception {
    HarnessCatalog catalog = catalog(Duration.ofMillis(10));
    LatchingTransport transport = new LatchingTransport();
    // 极小执行预算 + 足够大的恢复窗：预算过去很久后仍应停留在等待中
    transport.delegate.defaultTerminalDeliveryGrace = Duration.ofSeconds(10);

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run = ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-grace"));
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      EnvironmentCapabilityExecutionListener listener =
          transport.delegate.invocations.get(0).listener();
      String capabilityCallId = transport.delegate.invocations.get(0).request().call().id();

      // 有界 join 远超执行预算：worker 仍存活证明预算已过且仍在恢复窗内等待
      worker.join(250);
      assertTrue(worker.isAlive(), "worker must keep waiting after the execution budget");
      assertNull(completion.get(), "no terminal may be delivered before the remote completes");

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

  /** invoke 内同步投递首帧，持续 partial 不得延长 deadline，到期仍收敛为 UNKNOWN。 */
  @Test
  void continuousPartialsDoNotExtendSingleAbsoluteDeadline() throws Exception {
    HarnessCatalog catalog = catalog(Duration.ofSeconds(1));
    LatchingTransport transport = new LatchingTransport();
    transport.sendInitialPartial = true;
    transport.delegate.defaultTerminalDeliveryGrace = Duration.ZERO;

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-partial"));
    List<ToolResult> partials = new CopyOnWriteArrayList<>();
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partials::add)));

    AtomicBoolean streaming = new AtomicBoolean(true);
    Thread partialSender = null;
    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      EnvironmentCapabilityExecutionListener listener =
          transport.delegate.invocations.get(0).listener();
      String capabilityCallId = transport.delegate.invocations.get(0).request().call().id();

      assertFalse(partials.isEmpty(), "the first partial must be delivered synchronously");

      // 其余 partial 持续发送到调用结束，而不是固定次数
      partialSender =
          Thread.ofVirtual().start(() -> streamPartials(listener, capabilityCallId, streaming));

      // 宽松 watchdog：若 partial 续期 deadline，持续发送会让调用永不结束
      worker.join(5_000);
      assertFalse(
          worker.isAlive(), "continuous partials must not keep the call alive past the deadline");
      assertTrue(streaming.get(), "partial stream must still be active when the call ends");
      assertTrue(partialSender.isAlive(), "partial stream must continue until the call ends");

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
      if (partialSender != null) {
        partialSender.interrupt();
        partialSender.join(1_000);
      }
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  private static void streamPartials(
      EnvironmentCapabilityExecutionListener listener,
      String capabilityCallId,
      AtomicBoolean streaming) {
    int index = 1;
    try {
      while (streaming.get()) {
        listener.onPartial(
            new EnvironmentCapabilityResult(
                capabilityCallId, List.of(new TextResultContent("chunk-" + index)), false, "{}"));
        index++;
        Thread.sleep(5);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** 测试意图：工作线程阻塞等待被外部中断时，立即调用句柄的 terminate(CANCELLED)（非单纯 cancel）， 退出执行并收敛为 Cancelled 终态。 */
  @Test
  void interruptionInvokesTerminateWithCancelledCauseAndCleansUp() throws Exception {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    // 长超时，保证只有中断才会退出
    transport.delegate.defaultTerminalDeliveryGrace = Duration.ofSeconds(10);

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

  /** 测试意图：timeout 为 0 是显式无界执行预算，即使 handle 给出恢复窗也不得把它缩短为有界等待；恢复窗不改变零预算的无界语义。 */
  @Test
  void zeroTimeoutWaitsUnboundedRegardlessOfGrace() throws Exception {
    HarnessCatalog catalog = catalog(Duration.ZERO);
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminalDeliveryGrace = Duration.ofMillis(10);

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-unbounded"));
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      // 有界 join 远超恢复窗仍存活，证明零 timeout 不会被 grace 缩短为有界等待
      worker.join(500);
      assertTrue(
          worker.isAlive(), "zero timeout must remain unbounded beyond terminal delivery grace");
      assertNull(completion.get(), "zero timeout must not deliver a timeout outcome");

      EnvironmentCapabilityExecutionListener listener =
          transport.delegate.invocations.get(0).listener();
      String capabilityCallId = transport.delegate.invocations.get(0).request().call().id();
      listener.onComplete(
          new EnvironmentCapabilityResult(
              capabilityCallId, List.of(new TextResultContent("unbounded")), false, "{}"));

      worker.join(3_000);
      assertFalse(worker.isAlive(), "worker must complete after the terminal arrives");
      ToolGateway.Succeeded succeeded =
          assertInstanceOf(ToolGateway.Succeeded.class, completion.get());
      assertEquals("call-unbounded", succeeded.success().result().toolCallId());
      assertTrue(
          transport.delegate.returnedHandles.get(0).terminations().isEmpty(),
          "unbounded wait must not terminate the handle");
    } finally {
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  /** 测试意图：terminal 在 invoke 返回句柄前已经完成时，等待立即交付真实结果，不额外启动 deadline 或 terminate。 */
  @Test
  void immediateCompletionReturnsBeforeWaitWithoutTermination() {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminalDeliveryGrace = Duration.ofSeconds(30);
    transport.delegate.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.delegate.syncResult =
        new ToolResult("call-immediate", List.of(new TextResultContent("immediate")), false, "{}");

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-immediate"));
    try {
      ToolGateway.Succeeded succeeded =
          assertInstanceOf(ToolGateway.Succeeded.class, run.execute(partial -> {}));
      assertEquals("call-immediate", succeeded.success().result().toolCallId());
      assertEquals(
          "immediate", ((TextResultContent) succeeded.success().result().contents().get(0)).text());
      assertTrue(
          transport.delegate.returnedHandles.get(0).terminations().isEmpty(),
          "already completed terminal must not trigger termination");
    } finally {
      run.close();
    }
  }

  /** 测试意图：null 与负 grace 是句柄契约违规，必须显式失败并终止仍在途的远端执行，绝不能静默按 ZERO 兜底。 */
  @Test
  void nullAndNegativeGraceFailClosedAndTerminateInFlight() {
    assertInvalidGrace(null);
    assertInvalidGrace(Duration.ofMillis(-1));
  }

  private void assertInvalidGrace(Duration grace) {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminalDeliveryGrace = grace;

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-grace-invalid"));
    try {
      ToolGateway.Unknown unknown =
          assertInstanceOf(ToolGateway.Unknown.class, run.execute(partial -> {}));
      assertEquals(ToolExecutionGateway.EXECUTION_FAILED_KIND, unknown.error().kind());
      assertEquals(
          List.of(EnvironmentCapabilityTerminationCause.CANCELLED),
          transport.delegate.returnedHandles.get(0).terminations(),
          "invalid grace must terminate the in-flight execution");
    } finally {
      run.close();
    }
  }

  /** 测试意图：grace getter 抛错也必须终止在途执行；终止本身再失败只作为 suppressed，不得取代原始失败或让调用崩溃。 */
  @Test
  void graceGetterFailureTerminatesInFlightAndCleanupFailureIsSuppressed() {
    HarnessCatalog catalog = catalog();
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminalDeliveryGraceFailure =
        new IllegalStateException("grace getter failed");
    transport.delegate.defaultTerminateFailure = new IllegalStateException("terminate failed");

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-grace-getter"));
    try {
      ToolGateway.Unknown unknown =
          assertInstanceOf(ToolGateway.Unknown.class, run.execute(partial -> {}));
      assertEquals(ToolExecutionGateway.EXECUTION_FAILED_KIND, unknown.error().kind());
      assertEquals(
          1,
          transport.delegate.returnedHandles.get(0).terminateAttempts(),
          "cleanup must still attempt terminate");
      assertEquals(
          List.of(transport.delegate.defaultTerminateFailure),
          List.of(transport.delegate.defaultTerminalDeliveryGraceFailure.getSuppressed()),
          "cleanup failure must remain attached to the original failure");
    } finally {
      run.close();
    }
  }

  /** 测试意图：timeout + grace 的 Duration 加法或 nanosecond 换算溢出时 fail closed，保持原异常分类并终止在途执行。 */
  @Test
  void budgetOverflowFailsClosedAndTerminatesInFlight() {
    assertBudgetOverflow(Duration.ofSeconds(1));
    assertBudgetOverflow(Duration.ZERO);
  }

  private void assertBudgetOverflow(Duration grace) {
    HarnessCatalog catalog = timeoutArgumentCatalog();
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminalDeliveryGrace = grace;

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway,
            request(catalog, "call-overflow", "{\"timeout_seconds\":9223372036854775807}"));
    try {
      ToolGateway.Unknown unknown =
          assertInstanceOf(ToolGateway.Unknown.class, run.execute(partial -> {}));
      assertEquals(ToolExecutionGateway.EXECUTION_FAILED_KIND, unknown.error().kind());
      assertEquals(
          List.of(EnvironmentCapabilityTerminationCause.CANCELLED),
          transport.delegate.returnedHandles.get(0).terminations(),
          "overflow must terminate the in-flight execution");
    } finally {
      run.close();
    }
  }

  /** 测试意图：真实远端 FAILED 在执行预算耗尽后的恢复窗内交付时保持为确定失败，绝不降级成 UNKNOWN。 */
  @Test
  void remoteFailureWithinGraceRemainsFailedNotUnknown() throws Exception {
    HarnessCatalog catalog = catalog(Duration.ofMillis(10));
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminalDeliveryGrace = Duration.ofSeconds(10);

    ToolExecutionGateway gateway = gateway(catalog, transport);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, request(catalog, "call-remote-failed"));
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));

    try {
      assertTrue(transport.invoked.await(5, TimeUnit.SECONDS), "transport must be invoked");
      EnvironmentCapabilityExecutionListener listener =
          transport.delegate.invocations.get(0).listener();

      // 有界 join 远超执行预算：worker 仍存活证明预算已过且仍在恢复窗内等待
      worker.join(250);
      assertTrue(worker.isAlive(), "worker must keep waiting after the execution budget");
      assertNull(completion.get(), "no terminal may be delivered before the remote completes");

      listener.onError(new EnvironmentCapabilityFailedException("exit 1"));

      worker.join(3_000);
      assertFalse(worker.isAlive(), "worker must complete on the real terminal");
      ToolGateway.Failed failed = assertInstanceOf(ToolGateway.Failed.class, completion.get());
      assertEquals(ToolExecutionGateway.EXECUTION_FAILED_KIND, failed.error().kind());
      assertTrue(failed.error().message().contains("exit 1"));
      assertFalse(failed.retryable());
      assertTrue(
          transport.delegate.returnedHandles.get(0).terminations().isEmpty(),
          "real remote failure already ended the execution and must not be terminated again");
    } finally {
      worker.interrupt();
      worker.join(5_000);
      run.close();
    }
  }

  /** latch 确认 invoke 后交付正常终态，不触发 terminate 或 cancel。 */
  @Test
  void normalExecutionCompletesOnceWithoutTermination() throws Exception {
    HarnessCatalog catalog = catalog(Duration.ofSeconds(30));
    LatchingTransport transport = new LatchingTransport();
    transport.delegate.defaultTerminalDeliveryGrace = Duration.ZERO;

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
    return catalog(CAPABILITY.inputSchema(), CAPABILITY_TIMEOUT);
  }

  private static HarnessCatalog catalog(Duration timeout) {
    return catalog(CAPABILITY.inputSchema(), timeout);
  }

  private static HarnessCatalog timeoutArgumentCatalog() {
    return catalog(TIMEOUT_SCHEMA, CAPABILITY_TIMEOUT);
  }

  private static HarnessCatalog catalog(InputSchema schema, Duration timeout) {
    EnvironmentCapabilityDescriptor capability =
        new EnvironmentCapabilityDescriptor(CAPABILITY.id(), CAPABILITY.version(), schema, timeout);
    ToolDescriptor descriptor =
        new ToolDescriptor(
            TOOL_DESCRIPTOR.name(),
            TOOL_DESCRIPTOR.description(),
            TOOL_DESCRIPTOR.rendererKey(),
            schema,
            TOOL_DESCRIPTOR.sideEffect(),
            timeout);
    EnvironmentCapabilityTool tool = new EnvironmentCapabilityTool(descriptor, capability);
    return HarnessCatalog.from(
        List.of(
            HarnessContributor.of(
                new ContributorDescriptor(new ContributorId("remote"), "Remote", "1", Set.of()),
                registrar ->
                    registrar.registerTool("remote-tool", tool, ToolVisibility.SELECTABLE, 0))));
  }

  private static ToolInvocationRequest request(HarnessCatalog catalog, String callId) {
    return request(catalog, callId, "{}");
  }

  private static ToolInvocationRequest request(
      HarnessCatalog catalog, String callId, String argumentsJson) {
    ToolContribution contribution = catalog.findTool("remote-tool").orElseThrow();
    return new ToolInvocationRequest(
        new ToolCall(callId, "remote-tool", argumentsJson),
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
    private boolean sendInitialPartial;

    @Override
    public EnvironmentCapabilityExecutionHandle invoke(
        EnvironmentId environmentId,
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      EnvironmentCapabilityExecutionHandle handle =
          delegate.invoke(environmentId, request, listener);
      if (sendInitialPartial) {
        listener.onPartial(
            new EnvironmentCapabilityResult(
                request.call().id(), List.of(new TextResultContent("chunk-0")), false, "{}"));
      }
      invoked.countDown();
      return handle;
    }
  }
}
