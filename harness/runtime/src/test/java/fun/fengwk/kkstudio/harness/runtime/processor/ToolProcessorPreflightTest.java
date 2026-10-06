package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;

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
   * Allow：同一短事务顺序 markApprovalNotRequired -&gt; beginDispatch（Thread version 只 +1），随后 markRunning
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
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
   * Ask：READY -&gt; WAITING_APPROVAL(request reason)，version+1，complete TOOL Work；不 request
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
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Deny：READY -&gt; FAILED(error)，version+1，request THREAD Work，complete。 */
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
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * preflight 抛异常（确定无执行）：保持 READY / approval null / version 不变，按 preflightFailureDelay reschedule。
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
            Duration.ofSeconds(5));

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
            Duration.ofSeconds(1));
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
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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

  /** YOLO=true 不改变 WAITING_APPROVAL：已进入审批的 Tool 保留原审批请求，不因打开 YOLO 自动放行（不执行、不 bump version）。 */
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** FOLLOW 子代理读取真实执行根：root ENABLE 时子代理同样 Allow，且绝不调用 gateway.preflight。 */
  @Test
  void followChildReadsEnabledRootWithoutGatewayPreflight() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.followChildFixture(true, 1);
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(1, fixture.gateway.startCalls);
    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status());
    assertEquals(fixture.baseline.threadId(), fixture.gateway.executions.get(0).threadId());
  }

  /** FOLLOW 子代理读取真实执行根：root DISABLE 时子代理走正常 gateway preflight，不因自身无开关而静默 Allow。 */
  @Test
  void followChildReadsDisabledRootAndPreflights() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.followChildFixture(false, 1);
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(1, fixture.gateway.preflightCallsCount);
    assertEquals(1, fixture.gateway.startCalls);
    assertEquals(fixture.request, fixture.gateway.preflightCalls.get(0).request());
  }

  /** FOLLOW 目标不是执行根（跟随中间节点）时 fail closed：显式抛错，绝不 preflight / dispatch。 */
  @Test
  void followChainTargetingNonRootFailsClosed() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.followChildFixture(false, 2);

    assertThrows(
        IllegalStateException.class,
        () ->
            fixture.processor.process(
                ToolProcessorTestSupport.claim(
                    fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * 权限一致性反例：child 的真实执行根 DISABLE，但 policy 错指另一棵 ENABLE 的无关根。必须 fail closed——不得读取无关根开关作为 Allow 依据，不得
   * preflight / dispatch，也不得变更 invocation、child 或无关根行。
   */
  @Test
  void followChildTargetingUnrelatedEnabledRootFailsClosed() {
    ToolProcessorTestSupport.MisrootedFixture fixture =
        ToolProcessorTestSupport.misrootedFollowChildFixture(null);
    long childVersionBefore =
        ToolProcessorTestSupport.thread(fixture.store(), fixture.child().threadId()).version();
    long unrelatedRootVersionBefore =
        ToolProcessorTestSupport.thread(fixture.store(), fixture.unrelatedRoot().threadId())
            .version();

    assertThrows(
        IllegalStateException.class,
        () ->
            fixture
                .processor()
                .process(
                    ToolProcessorTestSupport.claim(
                        fixture.store(),
                        fixture.seeded().toolInvocationId(),
                        ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway().preflightCallsCount);
    assertEquals(0, fixture.gateway().startCalls);
    assertFalse(fixture.processor().hasActiveExecution());
    ToolInvocation tool =
        ToolProcessorTestSupport.tool(fixture.store(), fixture.seeded().toolInvocationId());
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(0, tool.attempt());
    assertNull(tool.approval());
    assertEquals(
        childVersionBefore,
        ToolProcessorTestSupport.thread(fixture.store(), fixture.child().threadId()).version());
    ThreadState unrelatedRoot =
        ToolProcessorTestSupport.thread(fixture.store(), fixture.unrelatedRoot().threadId());
    assertEquals(unrelatedRootVersionBefore, unrelatedRoot.version());
    assertTrue(unrelatedRoot.yoloPolicy().isEnabled());
  }

  /** Follow 目标（根）不存在时同样 fail closed：不 preflight / dispatch，invocation 仍保持 READY。 */
  @Test
  void followChildTargetingMissingRootFailsClosed() {
    ToolProcessorTestSupport.MisrootedFixture fixture =
        ToolProcessorTestSupport.misrootedFollowChildFixture(new UUID(0L, 999L));

    assertThrows(
        IllegalStateException.class,
        () ->
            fixture
                .processor()
                .process(
                    ToolProcessorTestSupport.claim(
                        fixture.store(),
                        fixture.seeded().toolInvocationId(),
                        ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway().preflightCallsCount);
    assertEquals(0, fixture.gateway().startCalls);
    assertFalse(fixture.processor().hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store(), fixture.seeded().toolInvocationId())
            .status());
  }

  /**
   * 坏存储读投影（fail-closed）：FOLLOW 子代理的祖先链与取锁步骤全部成功，但事务内读取真实执行根返回 empty。 判定必须以 root missing
   * 显式抛出，绝不把缺失根当作 DISABLE 继续 preflight / dispatch。
   */
  @Test
  void followChildMissingRootReadFailsClosed() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.followChildFixture(false, 1);
    UUID childThreadId = fixture.baseline.threadId();
    UUID rootThreadId = executionRoot(fixture.store, childThreadId);
    ThreadState realRoot =
        fixture.store.transaction(tx -> tx.findThread(rootThreadId).orElseThrow());
    long childVersionBefore =
        ToolProcessorTestSupport.thread(fixture.store, childThreadId).version();
    long rootVersionBefore = ToolProcessorTestSupport.thread(fixture.store, rootThreadId).version();
    // 同一事务内第一次 root findThread 供祖先锁复核，随后真实根读取返回 empty。
    ToolProcessor processor =
        injectedProcessor(
            fixture,
            wrapFindThread(
                fixture.store,
                rootThreadId,
                index -> index == 0 ? Optional.of(realRoot) : Optional.empty()));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                processor.process(
                    ToolProcessorTestSupport.claim(
                        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        "yolo execution root " + rootThreadId + " does not exist for thread " + childThreadId,
        failure.getMessage());
    assertFailClosedWithoutDispatch(
        fixture, processor, childThreadId, rootThreadId, childVersionBefore, rootVersionBefore);
  }

  /**
   * 坏存储读投影（fail-closed）：真实执行根读取返回一个 {@code yoloPolicy=FOLLOW} 的根投影（存储读不一致）。判定必须拒绝 FOLLOW 根，绝不
   * preflight / dispatch / 写版本。
   */
  @Test
  void followChildRootReadProjectedAsFollowFailsClosed() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.followChildFixture(false, 1);
    UUID childThreadId = fixture.baseline.threadId();
    UUID rootThreadId = executionRoot(fixture.store, childThreadId);
    ThreadState realRoot =
        fixture.store.transaction(tx -> tx.findThread(rootThreadId).orElseThrow());
    ThreadState projectedRoot = spy(realRoot);
    doReturn(ThreadYoloPolicy.follow(new UUID(0L, 42L))).when(projectedRoot).yoloPolicy();
    long childVersionBefore =
        ToolProcessorTestSupport.thread(fixture.store, childThreadId).version();
    long rootVersionBefore = ToolProcessorTestSupport.thread(fixture.store, rootThreadId).version();
    ToolProcessor processor =
        injectedProcessor(
            fixture,
            wrapFindThread(fixture.store, rootThreadId, index -> Optional.of(projectedRoot)));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                processor.process(
                    ToolProcessorTestSupport.claim(
                        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        "thread "
            + rootThreadId
            + " follows another thread and cannot be the execution root of "
            + childThreadId,
        failure.getMessage());
    assertFailClosedWithoutDispatch(
        fixture, processor, childThreadId, rootThreadId, childVersionBefore, rootVersionBefore);
  }

  /** FOLLOW 子代理的真实执行根：祖先链末位（head-to-root）。 */
  private static UUID executionRoot(InMemoryHarnessStore store, UUID threadId) {
    List<UUID> chain = store.transaction(tx -> tx.findAncestorChain(threadId));
    return chain.get(chain.size() - 1);
  }

  /**
   * 坏存储读投影注入：委托真实 store 与事务，仅对 {@code threadId} 的 {@code findThread} 读取按调用序号返回不一致投影；取锁与其余读取
   * 全部走真实委托，保证不破坏实际锁流程、也不在更早 guard 抛错。
   */
  private static HarnessStore wrapFindThread(
      InMemoryHarnessStore store, UUID threadId, IntFunction<Optional<ThreadState>> projection) {
    return new HarnessStore() {
      @Override
      public <T> T transaction(Function<HarnessStore.Transaction, T> callback) {
        AtomicInteger reads = new AtomicInteger();
        return store.transaction(
            realTx -> {
              HarnessStore.Transaction injected =
                  mock(HarnessStore.Transaction.class, delegatesTo(realTx));
              doAnswer(ignored -> projection.apply(reads.getAndIncrement()))
                  .when(injected)
                  .findThread(threadId);
              return callback.apply(injected);
            });
      }

      @Override
      public void afterCommit(Runnable action) {
        store.afterCommit(action);
      }

      @Override
      public void assertNoAmbientTransaction() {
        store.assertNoAmbientTransaction();
      }
    };
  }

  /** 复用既有 fixture 状态，仅替换 store 为带坏读投影的窄委托。 */
  private static ToolProcessor injectedProcessor(
      ToolProcessorTestSupport.Fixture fixture, HarnessStore wrappedStore) {
    return new ToolProcessor(
        wrappedStore,
        fixture.gateway,
        fixture.sink,
        new ToolProcessorConfig(
            ToolProcessorTestSupport.LEASE_CONFIG,
            () -> ToolProcessorTestSupport.NO_RETRY,
            ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
            ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        fixture.clock,
        fixture.scheduler,
        Runnable::run);
  }

  /** 零派发、零写入断言：无 gateway 副作用、无 version 漂移、无活跃 execution、Tool 仍 READY。 */
  private static void assertFailClosedWithoutDispatch(
      ToolProcessorTestSupport.Fixture fixture,
      ToolProcessor processor,
      UUID childThreadId,
      UUID rootThreadId,
      long childVersionBefore,
      long rootVersionBefore) {
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(processor.hasActiveExecution());
    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(0, tool.attempt());
    assertNull(tool.approval());
    assertEquals(
        childVersionBefore,
        ToolProcessorTestSupport.thread(fixture.store, childThreadId).version());
    assertEquals(
        rootVersionBefore, ToolProcessorTestSupport.thread(fixture.store, rootThreadId).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
            dead,
            Runnable::run);
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
