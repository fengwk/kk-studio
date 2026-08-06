package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolProcessor Gateway admission 行为：READY + completed approval 同事务 beginDispatch 后 admission
 * 的五种确定性 结果、lost work 无 mutation、Started 的 markRunning 门控与 stale-start fence、lease margin。
 */
class ToolProcessorAdmissionTest {

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  private ToolProcessorTestSupport.Fixture approvedFixture() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    return fixture;
  }

  /** Started：markRunning + revision+1，保留本地 execution 与 heartbeat。 */
  @Test
  void startedMarksRunningAndKeepsLocalExecution() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(1, fixture.gateway.startCalls);
    assertEquals(fixture.toolInvocationId, fixture.gateway.executions.get(0).invocationId());
    assertEquals(fixture.baseline.threadId(), fixture.gateway.executions.get(0).threadId());
    assertEquals(1, fixture.gateway.executions.get(0).proposedAttempt());
    assertFalse(handle.isCancelled());
    assertTrue(fixture.processor.hasActiveExecution());
    assertNotNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** start 返回前（甚至同步）到达的 callback 必须先缓冲：callback 观察到的 durable 状态仍是 DISPATCHING。 */
  @Test
  void gatesCallbacksUntilDurableRunningIsCommitted() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    AtomicReference<ToolInvocationStatus> statusAtCallback = new AtomicReference<>();
    fixture.gateway.beforeStartReturn =
        listener -> {
          listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));
          listener.onSucceeded(
              ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));
          statusAtCallback.set(
              ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
        };
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    assertEquals(ToolInvocationStatus.DISPATCHING, statusAtCallback.get());

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals(1, fixture.sink.events.size());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Busy：DISPATCHING -&gt; READY + revision+1，按 retryAfter reschedule，attempt 不变。 */
  @Test
  void busyBouncesToReadyWithoutAdvancingAttempt() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.queueStart(new ToolGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(0, tool.attempt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertNotNull(toolWork);
    assertEquals(ToolProcessorTestSupport.NOW.plusSeconds(5), toolWork.availableAt());
    assertNull(toolWork.leaseToken());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Overloaded：与 Busy 相同的 bounce 语义，使用自己的 retryAfter。 */
  @Test
  void overloadedBouncesWithItsOwnRetryAfter() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.queueStart(new ToolGateway.Overloaded(Duration.ofSeconds(6)));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        ToolProcessorTestSupport.NOW.plusSeconds(6),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
  }

  /** start 抛异常（契约：肯定未接受）：同样 bounce，使用构造注入的 fallback delay。 */
  @Test
  void startExceptionBouncesWithFallbackDelay() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.queueStart(new IllegalStateException("gateway down"));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        toolWork.availableAt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** Rejected：rejectDispatch FAILED + revision+1 + THREAD wake + complete TOOL Work，attempt 不变。 */
  @Test
  void rejectedFailsInvocationAndWakesThread() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    ToolInvocationError error = new ToolInvocationError("REJECTED", "tool rejected");
    fixture.gateway.queueStart(new ToolGateway.Rejected(error));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals(0, tool.attempt());
    assertEquals(error, tool.error());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Indeterminate：unknown UNKNOWN（DISPATCHING 消费 proposed attempt）+ THREAD wake + complete。 */
  @Test
  void indeterminateTerminatesUnknownConsumingProposedAttempt() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    ToolInvocationError error = new ToolInvocationError("UNCERTAIN", "outcome unknown");
    fixture.gateway.queueStart(new ToolGateway.Indeterminate(error));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals(error, tool.error());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** start 返回 null（契约违反）：acceptance 不确定，按 Indeterminate 语义收敛 UNKNOWN(attempt+1)。 */
  @Test
  void nullStartTreatsAsIndeterminate() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.queueNullStart();

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals("GATEWAY_UNKNOWN", tool.error().kind());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** Busy 且期间 ownership 丢失（work 行被删）：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void busyWithLostWorkReturnsLostOwnership() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.beforeStartReturn =
        listener -> ToolProcessorTestSupport.deleteToolWork(fixture);
    fixture.gateway.queueStart(new ToolGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** start 抛异常且期间 ownership 丢失：同样 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void startExceptionWithLostWorkReturnsLostOwnership() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.beforeStartReturn =
        listener -> ToolProcessorTestSupport.deleteToolWork(fixture);
    fixture.gateway.queueStart(new IllegalStateException("gateway down"));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** Rejected 且期间 ownership 丢失：LOST_OWNERSHIP。 */
  @Test
  void rejectedWithLostWorkReturnsLostOwnership() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.beforeStartReturn =
        listener -> ToolProcessorTestSupport.deleteToolWork(fixture);
    fixture.gateway.queueStart(
        new ToolGateway.Rejected(new ToolInvocationError("REJECTED", "rejected")));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** Indeterminate 且期间 ownership 丢失：LOST_OWNERSHIP。 */
  @Test
  void indeterminateWithLostWorkReturnsLostOwnership() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.beforeStartReturn =
        listener -> ToolProcessorTestSupport.deleteToolWork(fixture);
    fixture.gateway.queueStart(
        new ToolGateway.Indeterminate(new ToolInvocationError("UNCERTAIN", "unknown")));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** Started 且 markRunning 事务期间 ownership 丢失：LOST + cancel handle，durable 保持 DISPATCHING。 */
  @Test
  void startedWithLostWorkDuringMarkRunningCancelsHandle() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.beforeStartReturn =
        listener -> ToolProcessorTestSupport.deleteToolWork(fixture);
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertTrue(handle.isCancelled());
    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * stale-start fence：A 在 heartbeat 启动后暂停；lease 过期后由**另一实例**（模拟另一 JVM，A 的本地 abandoned 检查不可见）以
   * token-2 recover UNKNOWN；A 恢复时 fence 校验 durable 已非 DISPATCHING → 立即 abandon 并 LOST，绝不调用 Gateway。
   */
  @Test
  void staleStartAfterCrossInstanceRecoveryNeverCallsGateway() throws Exception {
    CountDownLatch inHeartbeat = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ScheduledExecutorService base = ToolProcessorTestSupport.newScheduler();
    schedulers.add(base);
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  Object result = method.invoke(base, args);
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    inHeartbeat.countDown();
                    release.await(5, TimeUnit.SECONDS);
                  }
                  return result;
                });
    ToolProcessorTestSupport.Fixture fixtureA =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, false, hooked);
    ToolProcessorTestSupport.transition(
        fixtureA.store,
        fixtureA.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixtureA.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    ClaimedWork claimedA =
        ToolProcessorTestSupport.claim(
            fixtureA.store, fixtureA.toolInvocationId, ToolProcessorTestSupport.NOW, "token-1");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixtureA.processor.process(claimedA)));
    threadA.start();
    assertTrue(inHeartbeat.await(5, TimeUnit.SECONDS));

    fixtureA.clock.advance(Duration.ofSeconds(61));
    ToolProcessorTestSupport.replaceToolWork(fixtureA, ToolProcessorTestSupport.NOW);
    ClaimedWork claimedB =
        ToolProcessorTestSupport.claim(
            fixtureA.store, fixtureA.toolInvocationId, fixtureA.clock.instant(), "token-2");
    ToolProcessor processorB =
        new ToolProcessor(
            fixtureA.store,
            new ToolProcessorTestSupport.FakeToolGateway(),
            fixtureA.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixtureA.clock,
            ToolProcessorTestSupport.newScheduler());
    assertEquals(ProcessResult.TERMINATED, processorB.process(claimedB));

    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(0, fixtureA.gateway.startCalls, "stale start must never call the gateway");
    assertFalse(fixtureA.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixtureA.store, fixtureA.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixtureA.store, fixtureA.toolInvocationId).attempt());
    assertEquals(
        2,
        ToolProcessorTestSupport.thread(fixtureA.store, fixtureA.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixtureA.store, fixtureA.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixtureA.store, fixtureA.toolInvocationId));
  }

  /** claim lease 剩余不足：prepare READY 立即 renew 出完整 margin 再 admission。 */
  @Test
  void nearExpiryClaimGetsFullLeaseMarginBeforeDispatch() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store,
            fixture.toolInvocationId,
            ToolProcessorTestSupport.NOW,
            "near-expiry",
            ToolProcessorTestSupport.NOW.plusSeconds(1));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolProcessorTestSupport.NOW.plusSeconds(30), toolWork.leaseUntil());
    assertEquals("near-expiry", toolWork.leaseToken());
  }

  /** claim lease 剩余充足：不额外 renew，保持 dispatcher 写入的 lease。 */
  @Test
  void sufficientLeaseMarginIsNotRenewed() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(claimed.leaseUntil(), toolWork.leaseUntil());
  }

  /** 成功结果经 Started 到达：partial + success 顺序发布，revision 每次 +1。 */
  @Test
  void partialAndSuccessCallbacksPublishInOrder() {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));
    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));

    assertEquals(1, fixture.sink.events.size());
    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals("answer", ((TextToolContent) tool.result().contents().get(0)).text());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /**
   * Stop 的 durable 终止在 admission 期间获胜（Busy 返回前 tool 已被并发的另一组件收敛为 UNKNOWN）：bounce 二次校验失败，
   * LOST_OWNERSHIP 且无 mutation。
   */
  @Test
  void stopRacingBusyAdmissionIsLostWithoutMutation() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.queueStart(new ToolGateway.Busy(Duration.ofSeconds(5)));

    ProcessResult result =
        processWithBlockedStart(
            fixture,
            () ->
                ToolProcessorTestSupport.transition(
                    fixture.store,
                    fixture.toolInvocationId,
                    tool ->
                        tool.unknown(
                            new ToolInvocationError("CANCELLED", "stopped"),
                            ToolProcessorTestSupport.NOW)));

    assertEquals(ProcessResult.LOST_OWNERSHIP, result);
    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 同一 Stop 竞态下 Rejected 返回：rejectDispatch 二次校验失败，LOST_OWNERSHIP 且无 mutation。 */
  @Test
  void stopRacingRejectedAdmissionIsLostWithoutMutation() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    fixture.gateway.queueStart(
        new ToolGateway.Rejected(new ToolInvocationError("REJECTED", "rejected")));

    ProcessResult result =
        processWithBlockedStart(
            fixture,
            () ->
                ToolProcessorTestSupport.transition(
                    fixture.store,
                    fixture.toolInvocationId,
                    tool ->
                        tool.unknown(
                            new ToolInvocationError("CANCELLED", "stopped"),
                            ToolProcessorTestSupport.NOW)));

    assertEquals(ProcessResult.LOST_OWNERSHIP, result);
    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /**
   * 跨实例 stale markRunning：A 通过 stale-start fence 并进入 Gateway.start 后，另一实例 B 恢复 UNKNOWN；A 的 Started
   * handle 到达时 attach 成功但 markRunning 校验 durable 已非 DISPATCHING → 立即 abandon + cancel handle， 绝不写
   * RUNNING。
   */
  @Test
  void staleMarkRunningAfterCrossInstanceRecoveryCancelsHandle() throws Exception {
    CountDownLatch inStart = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ScheduledExecutorService base = ToolProcessorTestSupport.newScheduler();
    schedulers.add(base);
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    return null;
                  }
                  return method.invoke(base, args);
                });
    ToolProcessorTestSupport.Fixture fixtureA =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, false, hooked);
    ToolProcessorTestSupport.transition(
        fixtureA.store,
        fixtureA.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixtureA.gateway.beforeStartReturn =
        listener -> {
          inStart.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixtureA.gateway.queueStart(new ToolGateway.Started(handle));
    ClaimedWork claimedA =
        ToolProcessorTestSupport.claim(
            fixtureA.store, fixtureA.toolInvocationId, ToolProcessorTestSupport.NOW, "token-1");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixtureA.processor.process(claimedA)));
    threadA.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS), "A must be inside gateway.start");

    fixtureA.clock.advance(Duration.ofSeconds(61));
    ToolProcessorTestSupport.replaceToolWork(fixtureA, ToolProcessorTestSupport.NOW);
    ClaimedWork claimedB =
        ToolProcessorTestSupport.claim(
            fixtureA.store, fixtureA.toolInvocationId, fixtureA.clock.instant(), "token-2");
    ToolProcessor processorB =
        new ToolProcessor(
            fixtureA.store,
            new ToolProcessorTestSupport.FakeToolGateway(),
            fixtureA.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixtureA.clock,
            ToolProcessorTestSupport.newScheduler());
    assertEquals(ProcessResult.TERMINATED, processorB.process(claimedB));

    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertTrue(handle.isCancelled());
    assertFalse(fixtureA.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixtureA.store, fixtureA.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixtureA.store, fixtureA.toolInvocationId).attempt());
    assertEquals(
        2,
        ToolProcessorTestSupport.thread(fixtureA.store, fixtureA.baseline.threadId()).revision());
  }

  /**
   * Stop 的 durable 终止在 Started handle 返回前获胜（tool 已被并发收敛为 UNKNOWN）：attach 成功但 markRunning 校验 durable
   * 已非 DISPATCHING → abandon + cancel handle，绝不写 RUNNING。
   */
  @Test
  void stopRacingStartedAdmissionFailsMarkRunning() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = approvedFixture();
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    ProcessResult result =
        processWithBlockedStart(
            fixture,
            () ->
                ToolProcessorTestSupport.transition(
                    fixture.store,
                    fixture.toolInvocationId,
                    tool ->
                        tool.unknown(
                            new ToolInvocationError("CANCELLED", "stopped"),
                            ToolProcessorTestSupport.NOW)));

    assertEquals(ProcessResult.LOST_OWNERSHIP, result);
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /**
   * stale-start fence 的 attempt-mismatch 侧：A 在 heartbeat 启动处暂停期间，另一并发 actor 把 durable 收敛回
   * DISPATCHING 但 attempt 已 +1；A 恢复时 boundary 校验 attempt 不匹配 → 立即 abandon 并 LOST，绝不调用 Gateway。
   */
  @Test
  void attemptMismatchAtStartBoundaryIsLostWithoutGateway() throws Exception {
    CountDownLatch inHeartbeat = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ScheduledExecutorService base = ToolProcessorTestSupport.newScheduler();
    schedulers.add(base);
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    inHeartbeat.countDown();
                    try {
                      release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                      Thread.currentThread().interrupt();
                    }
                    return null;
                  }
                  return method.invoke(base, args);
                });
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, false, hooked);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW, "token-1");

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread thread = new Thread(() -> result.set(fixture.processor.process(claimed)));
    thread.start();
    assertTrue(inHeartbeat.await(5, TimeUnit.SECONDS), "A must be inside heartbeat start");

    // 并发 actor 完整走一遍 markRunning -> retryReady -> beginDispatch：attempt 0 -> 1，状态回到 DISPATCHING。
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markRunning(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.retryReady(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.beginDispatch(ToolProcessorTestSupport.NOW));

    release.countDown();
    thread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(0, fixture.gateway.startCalls, "stale start must never call the gateway");
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
  }

  /** 阻塞 Gateway.start 直到 {@code during} 完成，返回 process 结果。 */
  private ProcessResult processWithBlockedStart(
      ToolProcessorTestSupport.Fixture fixture, Runnable during) throws Exception {
    CountDownLatch inStart = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.beforeStartReturn =
        listener -> {
          inStart.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread thread =
        new Thread(
            () ->
                result.set(
                    fixture.processor.process(
                        ToolProcessorTestSupport.claim(
                            fixture.store,
                            fixture.toolInvocationId,
                            ToolProcessorTestSupport.NOW))));
    thread.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS), "process must be inside gateway.start");
    during.run();
    release.countDown();
    thread.join(5000);
    return result.get();
  }
}
