package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolProcessor 恢复与生命周期行为：旧 lease 恢复（RUNNING）、WAITING_APPROVAL / terminal 清理、伪造 token、duplicate /
 * concurrent process、close / cancel / heartbeat 竞态、wrong target、config 校验。
 */
class ToolProcessorRecoveryTest {

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() throws Exception {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  /** wrong target：非 TOOL claim 直接拒绝。 */
  @Test
  void rejectsNonToolClaim() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ClaimedWork threadClaim =
        fixture
            .store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, "thread-token", Duration.ofSeconds(60)))
            .orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(threadClaim));
  }

  /**
   * 新 claim 遇到旧 lease 过期的 RUNNING：UNKNOWN 保留 attempt + version+1 + THREAD wake + complete，绝不重放
   * Tool。
   */
  @Test
  void staleRunningLeaseRecoveryTerminatesUnknownWithoutGateway() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toRunning(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.claim(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, fixture.clock.instant());

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals("LEASE_EXPIRED", tool.error().kind());
    assertTrue(tool.error().message().contains("Last known status: RUNNING"));
    assertTrue(tool.error().message().contains("cannot be confirmed"));
    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** WAITING_APPROVAL 不应执行：仅在 ownership 有效时 complete TOOL Work，不 bump version、不 request THREAD。 */
  @Test
  void waitingApprovalCompletesWorkWithoutAnyMutation() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            ToolProcessorTestSupport.waitingApproval(tool, "reason", ToolProcessorTestSupport.NOW));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, tool.status());
    assertTrue(tool.approval().isUndecided());
    assertEquals(0, tool.attempt());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** terminal 行：确保 THREAD wake 后 complete，不重复 bump version。 */
  @Test
  void terminalCleanupWakesThreadWhenResultNotLinked() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toRunning(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.succeed(
                ToolProcessorTestSupport.successResult("call-1", new TextResultContent("ok")),
                ToolProcessorTestSupport.NOW));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool.status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** 恢复路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void recoveryWithDeletedWorkIsLost() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toRunning(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** terminal cleanup 路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void terminalCleanupWithDeletedWorkIsLost() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toRunning(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.succeed(
                ToolProcessorTestSupport.successResult("call-1", new TextResultContent("ok")),
                ToolProcessorTestSupport.NOW));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.SUCCEEDED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** 同一 JVM 内 recovery：新 token supersede 旧本地 execution 后按 durable RUNNING 恢复 UNKNOWN。 */
  @Test
  void sameJvmRecoverySupersedesStaleLocalExecution() throws Exception {
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
                fixture.store,
                fixture.toolInvocationId,
                ToolProcessorTestSupport.NOW,
                "token-old")));
    assertTrue(run.awaitExecuting());
    fixture.clock.advance(Duration.ofSeconds(61));
    ToolProcessorTestSupport.replaceToolWork(fixture, ToolProcessorTestSupport.NOW);
    ClaimedWork recovered =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, fixture.clock.instant(), "token-new");

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals(1, fixture.gateway.admitCalls);
    assertTrue(run.awaitExited());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** active RUNNING + 伪造不同 token 的 claim：Work-only 前置校验先拦截，合法 execution 不被 cancel。 */
  @Test
  void forgedDifferentTokenWhileActiveRunningIsLostWithoutCancelling() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    ClaimedWork forged =
        new ClaimedWork(
            claimed.target(), claimed.claimedWakeVersion(), "forged-token", claimed.leaseUntil());
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(forged));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(1L, run.exited.getCount(), "body must not be interrupted");
    assertTrue(fixture.processor.hasActiveExecution());
    assertEquals(
        "token-" + fixture.toolInvocationId,
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).leaseToken());
  }

  /** 顺序重复投递同一 claim（同 token）：LOST no-op，不 cancel、不 mutation。 */
  @Test
  void duplicateSequentialProcessWithSameClaimIsLostNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    long version =
        ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        version,
        ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1L, run.exited.getCount(), "body must not be interrupted");
    assertEquals(1, fixture.gateway.admitCalls);
    assertTrue(fixture.processor.hasActiveExecution());
  }

  /** 并发重复投递同一 claim：guard 覆盖 prepare 到 registry 插入，后到者 LOST no-op，绝不双发 Gateway。 */
  @Test
  void duplicateConcurrentProcessWithSameClaimIsLostNoOp() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    CountDownLatch inStart = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.admitHook =
        () -> {
          inStart.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    AtomicReference<ProcessResult> first = new AtomicReference<>();
    AtomicReference<ProcessResult> second = new AtomicReference<>();
    Thread firstThread = new Thread(() -> first.set(fixture.processor.process(claimed)));
    firstThread.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS));

    Thread secondThread = new Thread(() -> second.set(fixture.processor.process(claimed)));
    secondThread.start();
    secondThread.join(5000);
    assertEquals(ProcessResult.LOST_OWNERSHIP, second.get());

    release.countDown();
    firstThread.join(5000);
    assertEquals(ProcessResult.STARTED, first.get());
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(1L, run.exited.getCount(), "body must not be interrupted");
  }

  /** 并发不同 token：新 claim 抢占 guard 并 supersede 正在 admission 的旧 execution；旧 run 被释放且从不执行。 */
  @Test
  void concurrentNewTokenSupersedesTheAdmittingExecution() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    CountDownLatch inAdmit = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.admitHook =
        () -> {
          inAdmit.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    fixture.gateway.queueAdmit(new ToolGateway.Busy(Duration.ofSeconds(5)));
    ClaimedWork claimedA =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW, "token-A");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixture.processor.process(claimedA)));
    threadA.start();
    assertTrue(inAdmit.await(5, TimeUnit.SECONDS));

    ToolProcessorTestSupport.replaceToolWork(fixture, ToolProcessorTestSupport.NOW);
    ClaimedWork claimedB =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW, "token-B");
    fixture.gateway.admitHook = null;
    AtomicReference<ProcessResult> resultB = new AtomicReference<>();
    Thread threadB = new Thread(() -> resultB.set(fixture.processor.process(claimedB)));
    threadB.start();
    threadB.join(5000);
    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.RESCHEDULED, resultB.get());
    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(2, fixture.gateway.admitCalls);
    assertEquals(0, run.executes.get(), "the superseded run must never execute");
    assertEquals(1, run.closes.get());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** claim lease 已过期：typed LOST_OWNERSHIP，无任何 mutation。 */
  @Test
  void expiredClaimIsLostOwnershipWithoutMutation() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    fixture.clock.advance(Duration.ofSeconds(61));

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** Stop deleteWork 后：claim 行缺失同样 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void deletedWorkClaimIsLostOwnershipWithoutMutation() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** close 后拒绝新 process；close 幂等且不 shutdown 注入的 scheduler。 */
  @Test
  void closedProcessorRejectsProcessAndCloseIsIdempotent() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    fixture.processor.close();
    fixture.processor.close();

    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claimed));
    assertFalse(fixture.processor.hasActiveExecution());
    assertFalse(fixture.scheduler.isShutdown());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
  }

  /** close 在 admission 期间执行：工具尚未执行，run 被释放并 reschedule，不留新 execution。 */
  @Test
  void closeDuringAdmissionReleasesRunAndReschedules() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.admitHook = fixture.processor::close;
    ToolProcessorTestSupport.FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, run.executes.get());
    assertEquals(1, run.closes.get());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).leaseToken());
  }

  /**
   * close 在 process 通过入口检查之后、registry 插入之前完成：putIfAbsent 后的 closed 检查必须拦截——abandon 新 execution、 把仍
   * owned 的 READY reschedule，绝不启动 Gateway。
   */
  @Test
  void closeBeforeRegistryInsertBouncesReadyWithoutStartingGateway() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
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
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertNotNull(toolWork);
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        toolWork.availableAt());
    assertNull(toolWork.leaseToken());
  }

  /** close / 本地 cancel 在 heartbeat 启动窗口内获胜：abandoned 检查拦截，不启动 Gateway，READY reschedule。 */
  @Test
  void abandonDuringHeartbeatStartupBouncesWithoutStartingGateway() throws Exception {
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
    cancelId.set(fixture.toolInvocationId);
    processorRef.set(fixture.processor);
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        toolWork.availableAt());
    assertNull(toolWork.leaseToken());
  }

  /** scheduler 拒绝 heartbeat 且期间 ownership 已丢：按 bounce 实际结果返回 LOST_OWNERSHIP，不调用 Gateway。 */
  @Test
  void heartbeatSchedulerRejectionWithLostWorkReturnsLostOwnership() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ScheduledExecutorService base = ToolProcessorTestSupport.newScheduler();
    schedulers.add(base);
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    ToolProcessorTestSupport.deleteToolWork(fixture);
                    throw new RejectedExecutionException("shutdown");
                  }
                  return method.invoke(base, args);
                });
    ToolProcessor processor =
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
            hooked,
            Runnable::run);
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(claimed));

    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertFalse(processor.hasActiveExecution());
  }

  /** cancel 只关闭本地执行（interrupt + heartbeat），不反写任何 durable 状态。 */
  @Test
  void cancelStopsLocalExecutionWithoutDurableWrite() throws Exception {
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

    assertTrue(run.awaitExecuting());
    assertTrue(fixture.processor.cancel(fixture.toolInvocationId));
    assertTrue(run.awaitExited());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertTrue(fixture.sink.events.isEmpty());

    assertFalse(fixture.processor.cancel(fixture.toolInvocationId));
    assertThrows(NullPointerException.class, () -> fixture.processor.cancel(null));
  }

  /** close 取消全部本地 execution；durable 行保持 RUNNING 等待 lease 恢复。 */
  @Test
  void closeCancelsAllLocalExecutions() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.Seeded second = fixture.seedExtraTool();
    ToolProcessorTestSupport.transition(
        fixture.store,
        second.toolInvocationId(),
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeRun firstRun =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    ToolProcessorTestSupport.FakeRun secondRun =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, second.toolInvocationId(), ToolProcessorTestSupport.NOW)));

    assertTrue(firstRun.awaitExecuting());
    assertTrue(secondRun.awaitExecuting());
    fixture.processor.close();

    assertFalse(fixture.processor.hasActiveExecution());
    assertTrue(firstRun.awaitExited());
    assertTrue(secondRun.awaitExited());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, second.toolInvocationId()).status());
  }

  /** ToolProcessorConfig：两个 delay 必须为正且至少 1ms，其余参数非空。 */
  @Test
  void toolProcessorConfigValidates() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                Duration.ZERO,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                Duration.ofNanos(500),
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                Duration.ofNanos(500)));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolProcessorConfig(
                null,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolProcessor(
                fixture.store,
                fixture.gateway,
                fixture.sink,
                null,
                fixture.clock,
                fixture.scheduler,
                Runnable::run));
    new ToolProcessorConfig(
        ToolProcessorTestSupport.LEASE_CONFIG,
        () -> ToolProcessorTestSupport.NO_RETRY,
        ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
        ToolProcessorTestSupport.BUSY_FALLBACK_DELAY);
  }

  /** scheduler 拒绝 heartbeat 提交：视为无法维持 lease，bounce 为 RESCHEDULED 且不调用 Gateway。 */
  @Test
  void heartbeatSchedulerRejectionBouncesWithoutGatewayCall() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
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
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.RESCHEDULED,
        deadProcessor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
  }

  /** WAITING_APPROVAL 的 ownership 已丢失：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void waitingApprovalWithLostWorkIsLost() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            ToolProcessorTestSupport.waitingApproval(tool, "reason", ToolProcessorTestSupport.NOW));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.WAITING_APPROVAL,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
  }

  /** WAITING_APPROVAL 被 Stop 为 CANCELLED 后：terminal cleanup 只确保 THREAD wake 后 complete。 */
  @Test
  void cancelledWaitingApprovalCleanupWakesThread() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            ToolProcessorTestSupport.waitingApproval(tool, "reason", ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.cancel(
                new ToolInvocationError("CANCELLED", "stopped"), ToolProcessorTestSupport.NOW));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.CANCELLED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** 前置状态为 DENIED 决策（FAILED）：terminal cleanup 同样只确保 THREAD wake 后 complete。 */
  @Test
  void deniedTerminalCleanupWakesThread() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            ToolProcessorTestSupport.waitingApproval(tool, "reason", ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.decideApproval(
                ToolApprovalDecision.DENIED,
                id(1L),
                "actor",
                "no",
                ToolProcessorTestSupport.NOW,
                ToolProcessorTestSupport.NOW));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.FAILED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /**
   * 本地 cancel 与 Stop deleteWork 同时在 heartbeat 启动窗口内获胜：不启动 Gateway；bounce 因 work 已删失败，按实际 结果返回
   * LOST_OWNERSHIP（不得无条件 RESCHEDULED）。
   */
  @Test
  void cancelWithLostWorkDuringHeartbeatStartupReturnsLost() throws Exception {
    AtomicReference<ToolProcessor> processorRef = new AtomicReference<>();
    AtomicReference<ToolProcessorTestSupport.Fixture> fixtureRef = new AtomicReference<>();
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
                    ToolProcessorTestSupport.deleteToolWork(fixtureRef.get());
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
    cancelId.set(fixture.toolInvocationId);
    processorRef.set(fixture.processor);
    fixtureRef.set(fixture);
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).version());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** abandon 幂等：第二次调用不再 interrupt、不再通知 owner。 */
  @Test
  void abandonIsIdempotentAndReleasesOwnerOnce() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.toApproved(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    AtomicInteger releases = new AtomicInteger();
    ToolExecution execution =
        new ToolExecution(
            fixture.store,
            fixture.sink,
            claimed,
            fixture.baseline.threadId(),
            1,
            fixture.request,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            ignored -> releases.incrementAndGet());
    ToolProcessorTestSupport.FakeRun run =
        new ToolProcessorTestSupport.FakeRun(
            progress -> ToolProcessorTestSupport.blockUntilInterrupted());
    assertEquals(ProcessResult.STARTED, execution.start(run));
    assertTrue(run.awaitExecuting());

    execution.abandon();
    execution.abandon();

    assertTrue(execution.abandoned());
    assertEquals(1, releases.get());
    assertTrue(run.awaitExited());
  }
}
