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
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolProcessor preflight 阶段行为：READY + approval null 时事务外 preflight（期间 heartbeat 维持 lease），Allow /
 * Ask / Deny / 异常四结果收敛，提交前二次校验，completed approval 跳过 preflight。
 */
class ToolProcessorPreflightTest {

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  /**
   * Allow：同一短事务顺序 markApprovalNotRequired -&gt; beginDispatch（Thread revision 只 +1），随后 markRunning
   * +1，preflight 收到冻结 request。
   */
  @Test
  void allowDispatchesAndStarts() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(1, fixture.gateway.preflightCallsCount);
    assertEquals(fixture.request, fixture.gateway.preflightCalls.get(0).request());
    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status());
    assertEquals(1, tool.attempt());
    assertNotNull(tool.approval());
    assertFalse(tool.approval().required());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(1, fixture.gateway.startCalls);
    assertEquals(fixture.toolInvocationId, fixture.gateway.executions.get(0).invocationId());
    assertEquals(fixture.baseline.threadId(), fixture.gateway.executions.get(0).threadId());
    assertEquals(1, fixture.gateway.executions.get(0).proposedAttempt());
    assertEquals(fixture.request, fixture.gateway.executions.get(0).request());
    assertFalse(handle.isCancelled());
    assertTrue(fixture.processor.hasActiveExecution());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertNotNull(toolWork);
    assertEquals("token-" + fixture.toolInvocationId, toolWork.leaseToken());
  }

  /**
   * Ask：READY -&gt; WAITING_APPROVAL(request reason)，revision+1，complete TOOL Work；不 request
   * THREAD、不执行。
   */
  @Test
  void askWaitsForApprovalWithoutExecuting() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    fixture.gateway.queuePreflightAsk("needs confirmation");

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, tool.status());
    assertNotNull(tool.approval());
    assertTrue(tool.approval().required());
    assertTrue(tool.approval().isUndecided());
    assertEquals("needs confirmation", tool.approval().reason());
    assertEquals(0, tool.attempt());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Deny：READY -&gt; FAILED(error)，revision+1，request THREAD Work，complete。 */
  @Test
  void denyFailsAndWakesThread() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolInvocationError error = new ToolInvocationError("DENIED", "permission denied");
    fixture.gateway.queuePreflightDeny(error);

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals(error, tool.error());
    assertEquals(0, tool.attempt());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * preflight 抛异常（确定无执行）：保持 READY / approval null / revision 不变，按 preflightFailureDelay reschedule。
   */
  @Test
  void preflightExceptionReschedulesWithoutMutation() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    fixture.gateway.queuePreflightFailure(new IllegalStateException("permission service down"));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(0, tool.attempt());
    assertNull(tool.approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertNotNull(toolWork);
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY),
        toolWork.availableAt());
    assertNull(toolWork.leaseToken());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** preflight 返回 null（契约违反）：同样按 preflightFailureDelay 重排，无任何 durable mutation。 */
  @Test
  void preflightNullReschedulesWithoutMutation() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    fixture.gateway.queueNullPreflight();

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
  }

  /** READY + not-required approval：跳过 preflight，同事务 beginDispatch 后直接 admission。 */
  @Test
  void completedNotRequiredApprovalSkipsPreflight() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** READY + ALLOWED decision：同样跳过 preflight 直接 admission。 */
  @Test
  void completedAllowedApprovalSkipsPreflight() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            ToolProcessorTestSupport.waitingApproval(tool, "reason", ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> ToolProcessorTestSupport.decideAllowed(tool, ToolProcessorTestSupport.NOW));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
  }

  /**
   * preflight 期间 heartbeat 维持 lease：捕获 periodic beat 后在 preflight 阻塞中推进时钟并手动执行一次 beat，lease 必须
   * renew 到 now + leaseDuration，随后 Allow 仍能正常 dispatch。
   */
  @Test
  void heartbeatKeepsLeaseAliveDuringPreflight() throws Exception {
    AtomicReference<Runnable> beat = new AtomicReference<>();
    ScheduledExecutorService base = ToolProcessorTestSupport.newScheduler();
    schedulers.add(base);
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    beat.set((Runnable) args[0]);
                    return null;
                  }
                  return method.invoke(base, args);
                });
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, false, hooked);
    CountDownLatch inPreflight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.preflightHook =
        call -> {
          inPreflight.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store,
            fixture.toolInvocationId,
            ToolProcessorTestSupport.NOW,
            "token",
            ToolProcessorTestSupport.NOW.plusSeconds(5));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread thread = new Thread(() -> result.set(fixture.processor.process(claimed)));
    thread.start();
    assertTrue(inPreflight.await(5, TimeUnit.SECONDS), "preflight must be in flight");

    Runnable periodic = beat.get();
    assertNotNull(periodic, "heartbeat must be scheduled before preflight");
    fixture.clock.advance(Duration.ofSeconds(10));
    periodic.run();
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolProcessorTestSupport.NOW.plusSeconds(40), toolWork.leaseUntil());

    release.countDown();
    thread.join(5000);
    assertEquals(ProcessResult.STARTED, result.get());
    assertEquals(1, fixture.gateway.startCalls);
  }

  /** preflight 前 lease 剩余不足：ensure 出完整 margin 后再调 preflight / start。 */
  @Test
  void nearExpiryClaimGetsFullLeaseMarginBeforePreflight() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store,
            fixture.toolInvocationId,
            ToolProcessorTestSupport.NOW,
            "near-expiry",
            ToolProcessorTestSupport.NOW.plusSeconds(1));
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolProcessorTestSupport.NOW.plusSeconds(30), toolWork.leaseUntil());
    assertEquals("near-expiry", toolWork.leaseToken());
  }

  /**
   * YOLO=true：锁内 Thread 快照直接 Allow，绝不调用 ToolGateway.preflight / permission evaluator，但仍恰好启动一次
   * Tool（admission 段走既有 dispatch：registry / heartbeat / lease / cancel 语义完整保留）。
   */
  @Test
  void yoloTrueDispatchesWithoutGatewayPreflightAndStartsExactlyOnce() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, true);
    // 不 queue 任何 preflight result：若 YOLO 路径错误地调用 gateway.preflight 会立即抛
    // IllegalStateException，测试确定性失败。
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(1, fixture.gateway.startCalls);
    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status());
    assertEquals(1, tool.attempt());
    assertNotNull(tool.approval());
    assertFalse(tool.approval().required());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(fixture.toolInvocationId, fixture.gateway.executions.get(0).invocationId());
    assertEquals(fixture.baseline.threadId(), fixture.gateway.executions.get(0).threadId());
    assertEquals(1, fixture.gateway.executions.get(0).proposedAttempt());
    assertEquals(fixture.request, fixture.gateway.executions.get(0).request());
    assertFalse(handle.isCancelled());
    // registry / lease 语义与普通 Allow 路径一致：本地 execution 已注册，TOOL Work 持有 claim lease。
    assertTrue(fixture.processor.hasActiveExecution());
    assertTrue(fixture.processor.cancel(fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertNotNull(toolWork);
    assertEquals("token-" + fixture.toolInvocationId, toolWork.leaseToken());
  }

  /** YOLO=true 不改变 WAITING_APPROVAL：已进入审批的 Tool 保留原审批请求，不因打开 YOLO 自动放行（不执行、不 bump revision）。 */
  @Test
  void yoloTrueLeavesWaitingApprovalUntouched() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, true);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            ToolProcessorTestSupport.waitingApproval(
                tool, "needs confirmation", ToolProcessorTestSupport.NOW));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, tool.status());
    assertNotNull(tool.approval());
    assertTrue(tool.approval().required());
    assertTrue(tool.approval().isUndecided());
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** YOLO=true 时锁内快照已决策的 Allow 在提交前仍做二次校验：claim 丢失完整 no-op（不启动 gateway），与普通 Allow 路径一致。 */
  @Test
  void yoloDirectAllowLostClaimIsNoOp() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, true);
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    // 二次校验事务内 lockClaimedWork 失败：完整 no-op。
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** preflight 期间 ownership 丢失（Stop deleteWork）：二次校验失败，完整 no-op，绝不 start。 */
  @Test
  void lostClaimDuringPreflightIsNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    CountDownLatch inPreflight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.preflightHook =
        call -> {
          inPreflight.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

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
    assertTrue(inPreflight.await(5, TimeUnit.SECONDS));
    ToolProcessorTestSupport.deleteToolWork(fixture);
    release.countDown();
    thread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** preflight 期间 durable 状态被并发改写：二次校验（READY + attempt + approval null）失败，完整 no-op。 */
  @Test
  void stateChangedDuringPreflightIsNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    CountDownLatch inPreflight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.preflightHook =
        call -> {
          inPreflight.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

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
    assertTrue(inPreflight.await(5, TimeUnit.SECONDS));
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            ToolProcessorTestSupport.waitingApproval(
                tool, "other actor", ToolProcessorTestSupport.NOW));
    release.countDown();
    thread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(
        ToolInvocationStatus.WAITING_APPROVAL,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** close 在 preflight 期间获胜：不调 start，READY claim 仍 owned 时立即重排。 */
  @Test
  void closeDuringPreflightReschedulesReadyWork() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    CountDownLatch inPreflight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.preflightHook =
        call -> {
          inPreflight.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

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
    assertTrue(inPreflight.await(5, TimeUnit.SECONDS));
    fixture.processor.close();
    release.countDown();
    thread.join(5000);

    assertEquals(ProcessResult.RESCHEDULED, result.get());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
  }

  /** preflight 抛异常且期间 claim 已 lost：重排事务同样失败，LOST_OWNERSHIP。 */
  @Test
  void preflightExceptionWithLostClaimReturnsLostOwnership() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    CountDownLatch inPreflight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.preflightHook =
        call -> {
          inPreflight.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queuePreflightFailure(new IllegalStateException("boom"));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread thread = new Thread(() -> result.set(fixture.processor.process(claimed)));
    thread.start();
    assertTrue(inPreflight.await(5, TimeUnit.SECONDS));
    ToolProcessorTestSupport.deleteToolWork(fixture);
    release.countDown();
    thread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** preflight 返回 null 且期间 claim 已 lost：null 分支同样二次校验失败，LOST_OWNERSHIP。 */
  @Test
  void nullPreflightWithLostClaimReturnsLostOwnership() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processWithBlockedPreflight(
            fixture, fixture.gateway::queueNullPreflight, () -> deleteToolWork(fixture)));
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** Ask 结果提交前二次校验（claim 已 lost）：完整 no-op，绝不执行。 */
  @Test
  void askWithLostClaimIsNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processWithBlockedPreflight(
            fixture,
            () -> fixture.gateway.queuePreflightAsk("reason"),
            () -> deleteToolWork(fixture)));
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** Ask 结果提交前二次校验（approval 被并发引入）：完整 no-op。 */
  @Test
  void askWithStateChangedIsNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processWithBlockedPreflight(
            fixture,
            () -> fixture.gateway.queuePreflightAsk("reason"),
            () ->
                ToolProcessorTestSupport.transition(
                    fixture.store,
                    fixture.toolInvocationId,
                    tool ->
                        ToolProcessorTestSupport.waitingApproval(
                            tool, "other actor", ToolProcessorTestSupport.NOW))));
    assertEquals(
        ToolInvocationStatus.WAITING_APPROVAL,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** Deny 结果提交前二次校验（claim 已 lost）：完整 no-op。 */
  @Test
  void denyWithLostClaimIsNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processWithBlockedPreflight(
            fixture,
            () -> fixture.gateway.queuePreflightDeny(new ToolInvocationError("DENIED", "no")),
            () -> deleteToolWork(fixture)));
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** Deny 结果提交前二次校验（状态被并发改写）：完整 no-op。 */
  @Test
  void denyWithStateChangedIsNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processWithBlockedPreflight(
            fixture,
            () -> fixture.gateway.queuePreflightDeny(new ToolInvocationError("DENIED", "no")),
            () ->
                ToolProcessorTestSupport.transition(
                    fixture.store,
                    fixture.toolInvocationId,
                    tool ->
                        ToolProcessorTestSupport.waitingApproval(
                            tool, "other actor", ToolProcessorTestSupport.NOW))));
    assertEquals(
        ToolInvocationStatus.WAITING_APPROVAL,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** preflight 阶段 scheduler 拒绝 heartbeat：无法维持 lease，按 dispatchBusyFallbackDelay 重排，不调 preflight。 */
  @Test
  void preflightHeartbeatStartFailureReschedulesWithPreflightDelay() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ScheduledExecutorService dead = ToolProcessorTestSupport.newScheduler();
    schedulers.add(dead);
    dead.shutdownNow();
    ToolProcessor deadProcessor =
        new ToolProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            dead);
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        deadProcessor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** close 在 preflight registry 插入之前完成：立即 abandon，并把仍 owned 的 READY Work 重排。 */
  @Test
  void closeBeforeRegistryInsertInPreflightPathReschedules() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);

    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holdStore =
        new Thread(
            () ->
                fixture.store.transaction(
                    ignored -> {
                      holding.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    holdStore.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread processThread = new Thread(() -> result.set(fixture.processor.process(claimed)));
    processThread.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (processThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(
        System.nanoTime() < deadline, "process must block on the store monitor (claimOwned)");

    fixture.processor.close();
    release.countDown();
    holdStore.join(5000);
    processThread.join(5000);

    assertEquals(ProcessResult.RESCHEDULED, result.get());
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
  }

  /** 本地 cancel 在 preflight heartbeat 启动窗口内获胜：不调 preflight，仍 owned 的 READY Work 立即重排。 */
  @Test
  void cancelDuringHeartbeatStartupInPreflightPathReschedules() {
    AtomicReference<ToolProcessor> processorRef = new AtomicReference<>();
    AtomicReference<UUID> cancelId = new AtomicReference<>();
    ScheduledExecutorService base = ToolProcessorTestSupport.newScheduler();
    schedulers.add(base);
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    processorRef.get().cancel(cancelId.get());
                    return null;
                  }
                  return method.invoke(base, args);
                });
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, false, hooked);
    cancelId.set(fixture.toolInvocationId);
    processorRef.set(fixture.processor);
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).approval());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
  }

  /** preflight 抛异常且期间 durable 状态被并发改写：重排事务二次校验失败，LOST_OWNERSHIP。 */
  @Test
  void preflightExceptionWithStateChangedIsLost() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processWithBlockedPreflight(
            fixture,
            () -> fixture.gateway.queuePreflightFailure(new IllegalStateException("boom")),
            () ->
                ToolProcessorTestSupport.transition(
                    fixture.store,
                    fixture.toolInvocationId,
                    tool ->
                        ToolProcessorTestSupport.waitingApproval(
                            tool, "other actor", ToolProcessorTestSupport.NOW))));
    assertEquals(
        ToolInvocationStatus.WAITING_APPROVAL,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** 阻塞 preflight 直到 {@code during} 完成，返回 process 结果。 */
  private ProcessResult processWithBlockedPreflight(
      ToolProcessorTestSupport.Fixture fixture, Runnable queueResult, Runnable during)
      throws Exception {
    CountDownLatch inPreflight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.preflightHook =
        call -> {
          inPreflight.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    queueResult.run();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
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
    assertTrue(inPreflight.await(5, TimeUnit.SECONDS));
    during.run();
    release.countDown();
    thread.join(5000);
    return result.get();
  }

  private void deleteToolWork(ToolProcessorTestSupport.Fixture fixture) {
    ToolProcessorTestSupport.deleteToolWork(fixture);
  }
}
