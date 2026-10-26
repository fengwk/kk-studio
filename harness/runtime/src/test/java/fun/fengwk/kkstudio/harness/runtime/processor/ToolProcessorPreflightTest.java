package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolProcessor preflight 阶段行为：READY + approval null 时事务外 preflight（期间 heartbeat 维持 lease），Allow /
 * Ask / Deny / 异常四结果收敛，提交前二次校验，completed approval 跳过 preflight。
 */
class ToolProcessorPreflightTest {

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  /** 已过期 A 恢复时不能取消 B，B 的执行结果仍可正常提交。 */
  @Test
  void expiredClaimCannotCancelNewOwnerAfterOwnershipCheck() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicReference<Thread> worker = new AtomicReference<>();
    HarnessStore gated = ClaimOwnershipTestSupport.gate(fixture.store, worker, checked, resume);
    ToolProcessor processor =
        new ToolProcessor(
            gated,
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            TestExecutionExitConfirmer.create(gated, fixture.clock));
    ClaimedWork claimA =
        ToolProcessorTestSupport.claim(
            fixture.store,
            fixture.toolInvocationId,
            fixture.clock.instant(),
            "token-A",
            Duration.ofSeconds(60));
    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread threadA = new Thread(() -> result.set(processor.process(claimA)));
    worker.set(threadA);
    try {
      threadA.start();
      assertTrue(checked.await(5, TimeUnit.SECONDS));
      fixture.clock.advance(Duration.ofSeconds(61));
      ClaimedWork claimB =
          ToolProcessorTestSupport.claim(
              fixture.store,
              fixture.toolInvocationId,
              fixture.clock.instant(),
              "token-B",
              Duration.ofSeconds(60));
      fixture.gateway.queuePreflightAllow();
      ToolProcessorTestSupport.FakeRun run =
          fixture.gateway.queueRun(
              progress -> {
                try {
                  if (!finish.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("completion gate timed out");
                  }
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError("new owner was interrupted", failure);
                }
                return new ToolGateway.Failed(
                    new ToolInvocationError("EXPECTED", "finished"), false);
              });
      assertEquals(ProcessResult.STARTED, processor.process(claimB));
      assertTrue(run.awaitExecuting());
      resume.countDown();
      threadA.join(5000);
      assertFalse(threadA.isAlive());
      assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
      assertEquals(0, run.closes.get());
      assertTrue(processor.hasActiveExecution());
      finish.countDown();
      ToolProcessorTestSupport.awaitTrue(
          () -> !processor.hasActiveExecution(), Duration.ofSeconds(5));
      assertEquals(
          new ToolInvocationError("EXPECTED", "finished"),
          ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).error());
    } finally {
      resume.countDown();
      finish.countDown();
      processor.close();
      threadA.join(5000);
    }
  }

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  /**
   * Allow：同一短事务顺序 markApprovalNotRequired（Thread version +1），随后 markRunning +1，preflight 收到冻结
   * request。
   */
  @Test
  void allowDispatchesAndStarts() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    fixture.gateway.queuePreflightAllow();
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(fixture.toolInvocationId, fixture.gateway.executions.get(0).invocationId());
    assertEquals(fixture.baseline.threadId(), fixture.gateway.executions.get(0).threadId());
    assertEquals(fixture.request, fixture.gateway.executions.get(0).request());
    assertEquals(0, run.closes.get());
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
    assertEquals(0, fixture.gateway.admitCalls);
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
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * 真实 prepare + Deny 拒绝回写路径：只锁 own Thread，再锁 Model/Tool/Work；不取 tree/ancestor 锁， 且即使当前 Thread
   * 是子代理也不锁 parent/root。
   */
  @Test
  void denyLocksOwnChildThreadOnlyWithoutTreeOrAncestors() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    fixture.gateway.queuePreflightDeny(new ToolInvocationError("DENIED", "permission denied"));
    ToolProcessorTestSupport.RecordingLockStore recording =
        new ToolProcessorTestSupport.RecordingLockStore(fixture.store);
    ToolProcessor processor =
        new ToolProcessor(
            recording,
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            TestExecutionExitConfirmer.create(recording, fixture.clock));

    assertEquals(
        ProcessResult.TERMINATED,
        processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ThreadState child = ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId());
    List<List<String>> locked = recording.threadLockTransactions();
    assertEquals(2, locked.size(), "prepare and deny each lock the own thread");
    for (List<String> ops : locked) {
      assertFalse(
          ops.stream()
              .anyMatch(
                  op ->
                      op.startsWith("lockTree")
                          || op.startsWith("findAncestorChain")
                          || op.startsWith("lockSession")),
          "processor must not take tree/session/ancestor locks: " + ops);
      assertEquals("lockThread:" + fixture.baseline.threadId(), ops.get(0));
      assertTrue(
          ops.stream()
              .filter(op -> op.startsWith("lockThread:"))
              .allMatch(op -> op.equals("lockThread:" + fixture.baseline.threadId())));
      int tool = indexOfPrefix(ops, "lockToolInvocation:");
      int work = indexOfPrefix(ops, "lockClaimedWork");
      assertTrue(tool > 0, "own Thread must precede Tool: " + ops);
      assertTrue(work > tool, "Tool must precede Work: " + ops);
      if (indexOfPrefix(ops, "lockModelInvocation:") >= 0) {
        assertTrue(
            indexOfPrefix(ops, "lockModelInvocation:") < tool, "Model must precede Tool: " + ops);
      }
    }
    assertTrue(!child.rootThreadId().equals(child.id()));
    assertFalse(
        locked.stream()
            .flatMap(List::stream)
            .anyMatch(
                op ->
                    op.equals("lockThread:" + child.rootThreadId())
                        || op.equals("lockThread:" + child.parentThreadId())),
        "child processor must not lock its execution root or parent");
  }

  private static int indexOfPrefix(List<String> ops, String prefix) {
    for (int i = 0; i < ops.size(); i++) {
      if (ops.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
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
    assertEquals(0, fixture.gateway.admitCalls);
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

  /** READY + not-required approval：跳过 preflight，直接 admission。 */
  @Test
  void completedNotRequiredApprovalSkipsPreflight() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
   * Processor Ask(review) 冻结后 ALLOWED 第二次 process： Gateway Execution 持有相同 approval 与 expectedFile。
   */
  @Test
  void askWithEditReviewFrozenThenAllowedDispatchesWithSameApprovalAndExpectedFile() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ExpectedFile expectedFile =
        new ExpectedFile(
            "/workspace/src/App.java",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    ToolEditReview editReview = new ToolEditReview(expectedFile, "-old\n+new");
    fixture.gateway.queuePreflightAsk("needs review", editReview);

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation toolWaiting =
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, toolWaiting.status());
    assertNotNull(toolWaiting.approval());
    assertTrue(toolWaiting.approval().required());
    assertEquals(editReview, toolWaiting.approval().editReview());

    UUID decisionId = UUID.randomUUID();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.decideApproval(
                ToolApprovalDecision.ALLOWED,
                decisionId,
                "user",
                "looks good",
                ToolProcessorTestSupport.NOW.plusSeconds(1),
                ToolProcessorTestSupport.NOW.plusSeconds(2)));
    ToolProcessorTestSupport.requestToolWork(fixture, ToolProcessorTestSupport.NOW.plusSeconds(2));

    ToolInvocation toolReady =
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.READY, toolReady.status());
    ToolApproval frozenApproval = toolReady.approval();
    assertNotNull(frozenApproval);

    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store,
                fixture.toolInvocationId,
                ToolProcessorTestSupport.NOW.plusSeconds(3))));

    assertEquals(1, fixture.gateway.executions.size());
    ToolGateway.Execution execution = fixture.gateway.executions.get(0);
    assertEquals(frozenApproval, execution.approval());
    assertEquals(expectedFile, execution.expectedFile());
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
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
    assertEquals(1, fixture.gateway.admitCalls);
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(1, fixture.gateway.admitCalls);
    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status());
    assertEquals(1, tool.attempt());
    assertNotNull(tool.approval());
    assertFalse(tool.approval().required());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(fixture.toolInvocationId, fixture.gateway.executions.get(0).invocationId());
    assertEquals(fixture.baseline.threadId(), fixture.gateway.executions.get(0).threadId());
    assertEquals(fixture.request, fixture.gateway.executions.get(0).request());
    assertEquals(0, run.closes.get());
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
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** YOLO=true 时锁内快照已决策的 Allow 在提交前仍做二次校验：claim 丢失完整 no-op（不启动 gateway），与普通 Allow 路径一致。 */
  @Test
  void yoloDirectAllowLostClaimIsNoOp() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY, true);
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 子代理读取真实执行根：root 开启时子代理同样 Allow，且绝不调用 gateway.preflight。 */
  @Test
  void childReadsEnabledRootWithoutGatewayPreflight() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.childFixture(true, 1);
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(1, fixture.gateway.admitCalls);
    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status());
    assertEquals(fixture.baseline.threadId(), fixture.gateway.executions.get(0).threadId());
  }

  /** 子代理读取真实执行根：root 关闭时子代理走正常 gateway preflight，不因自身无开关而静默 Allow。 */
  @Test
  void childReadsDisabledRootAndPreflights() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(1, fixture.gateway.preflightCallsCount);
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(fixture.request, fixture.gateway.preflightCalls.get(0).request());
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
    assertEquals(0, fixture.gateway.admitCalls);
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
    assertEquals(0, fixture.gateway.admitCalls);
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
    assertEquals(0, fixture.gateway.admitCalls);
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
    assertEquals(0, fixture.gateway.admitCalls);
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
    assertEquals(0, fixture.gateway.admitCalls);
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
    assertEquals(0, fixture.gateway.admitCalls);
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
    assertEquals(0, fixture.gateway.admitCalls);
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
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** preflight 阶段 scheduler 拒绝 heartbeat：无法维持 lease，按 admitFailureDelay 重排，不调 preflight。 */
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
            Runnable::run,
            TestExecutionExitConfirmer.create(fixture.store, fixture.clock));
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

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
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** close 在 preflight registry 插入之前完成：立即 abandon，并把仍 owned 的 READY Work 重排。 */
  @Test
  void closeBeforeRegistryInsertInPreflightPathReschedules() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
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
    assertEquals(0, fixture.gateway.admitCalls);
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.admitCalls);
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
    assertEquals(0, fixture.gateway.admitCalls);
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
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

  @ParameterizedTest
  @ValueSource(strings = {"ALLOW", "ASK", "DENY"})
  void preflightCommitFailureAbandonsExecutionAndHeartbeat(String resultKind) {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    UUID toolInvocationId = fixture.toolInvocationId;
    AtomicBoolean failNextTransaction = new AtomicBoolean();
    fixture.gateway.preflightHook = call -> failNextTransaction.set(true);
    switch (resultKind) {
      case "ALLOW" -> fixture.gateway.queuePreflightAllow();
      case "ASK" -> fixture.gateway.queuePreflightAsk("needs confirmation");
      case "DENY" -> fixture.gateway.queuePreflightDeny(
          new ToolInvocationError("DENIED", "permission denied"));
      default -> throw new IllegalArgumentException("unexpected result kind: " + resultKind);
    }

    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
    ArgumentCaptor<Runnable> heartbeatTask = ArgumentCaptor.forClass(Runnable.class);
    doReturn(heartbeatFuture)
        .when(scheduler)
        .scheduleAtFixedRate(heartbeatTask.capture(), anyLong(), anyLong(), any(TimeUnit.class));
    HarnessStore failingStore = failNextTransactionOnce(fixture.store, failNextTransaction);
    ToolProcessor processor =
        new ToolProcessor(
            failingStore,
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            scheduler,
            Runnable::run,
            TestExecutionExitConfirmer.create(failingStore, fixture.clock));
    ClaimedWork claim =
        ToolProcessorTestSupport.claim(
            fixture.store,
            toolInvocationId,
            ToolProcessorTestSupport.NOW,
            "token-failed",
            Duration.ofSeconds(10));

    try {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> processor.process(claim));
      assertEquals("injected transaction failure", failure.getMessage());

      // 提交失败后释放 execution 并取消 heartbeat。
      assertFalse(processor.hasActiveExecution(), "failed preflight commit must abandon execution");
      verify(heartbeatFuture).cancel(anyBoolean());

      ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, toolInvocationId);
      assertEquals(ToolInvocationStatus.READY, tool.status());
      assertEquals(0, tool.attempt());
      assertNull(tool.approval());
      ThreadState thread =
          ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId());
      assertEquals(0, thread.version());
      Work claimedWork = ToolProcessorTestSupport.toolWork(fixture.store, toolInvocationId);
      assertNotNull(claimedWork);
      assertEquals("token-failed", claimedWork.leaseToken());

      // 时钟推进到租约过期，验证 Work 可被正常重新 claim
      fixture.clock.advance(Duration.ofSeconds(35));
      boolean reclaimable =
          fixture.store.transaction(
              tx ->
                  tx.claimNextWork(WorkTargetType.TOOL, "token-new", Duration.ofSeconds(30))
                      .isPresent());
      assertTrue(
          reclaimable, "abandoned execution must allow Work to be reclaimed after lease expiry");
    } finally {
      processor.close();
    }
  }

  /** admission 已持有资源后 RUNNING 写库失败，run 与 heartbeat 均须释放。 */
  @Test
  void runningCommitFailureReleasesAdmittedRun() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    AtomicBoolean fail = new AtomicBoolean();
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.admitHook = () -> fail.set(true);
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              throw new AssertionError("must not execute");
            });
    ToolProcessor processor =
        new ToolProcessor(
            failNextTransactionOnce(fixture.store, fail),
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            TestExecutionExitConfirmer.create(fixture.store, fixture.clock));
    try {
      assertEquals(
          ProcessResult.LOST_OWNERSHIP,
          processor.process(
              ToolProcessorTestSupport.claim(
                  fixture.store, fixture.toolInvocationId, fixture.clock.instant())));
      assertEquals(1, run.closes.get());
      assertFalse(processor.hasActiveExecution());
      assertEquals(
          ToolInvocationStatus.READY,
          ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    } finally {
      processor.close();
    }
  }

  private static HarnessStore failNextTransactionOnce(
      HarnessStore delegate, AtomicBoolean failNext) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if ("transaction".equals(method.getName()) && failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("injected transaction failure");
              }
              return invokeUnchecked(delegate, method, args);
            });
  }

  private static Object invokeUnchecked(Object target, Method method, Object[] args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw new IllegalStateException(cause);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException(error);
    }
  }
}
