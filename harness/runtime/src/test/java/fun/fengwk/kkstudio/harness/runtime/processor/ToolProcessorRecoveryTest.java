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

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolProcessor 恢复与生命周期行为：旧 lease 恢复（DISPATCHING / RUNNING）、WAITING_APPROVAL / terminal 清理、伪造
 * token、duplicate / concurrent process、close / cancel / heartbeat 竞态、wrong target、config 校验。
 */
class ToolProcessorRecoveryTest {

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  /** wrong target：非 TOOL claim 直接拒绝。 */
  @Test
  void rejectsNonToolClaim() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ClaimedWork threadClaim =
        fixture
            .store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD,
                        ToolProcessorTestSupport.NOW,
                        "thread-token",
                        ToolProcessorTestSupport.NOW.plusSeconds(60)))
            .orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(threadClaim));
  }

  /**
   * 新 claim 遇到旧 lease 过期的 RUNNING：UNKNOWN 保留 attempt + revision+1 + THREAD wake + complete，绝不重放
   * Tool。
   */
  @Test
  void staleRunningLeaseRecoveryTerminatesUnknownWithoutGateway() {
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
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 新 claim 遇到旧 lease 过期的 DISPATCHING：UNKNOWN 消费 proposed attempt（attempt+1）。 */
  @Test
  void staleDispatchingLeaseRecoveryConsumesProposedAttempt() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toDispatched(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.claim(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, fixture.clock.instant());

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** WAITING_APPROVAL 不应执行：仅在 ownership 有效时 complete TOOL Work，不 bump revision、不 request THREAD。 */
  @Test
  void waitingApprovalCompletesWorkWithoutAnyMutation() {
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** terminal 行：确保 THREAD wake 后 complete，不重复 bump revision。 */
  @Test
  void terminalCleanupWakesThreadWhenResultNotLinked() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toRunning(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.succeed(
                ToolProcessorTestSupport.successResult("call-1", new TextToolContent("ok")),
                ToolProcessorTestSupport.NOW));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool.status());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** 恢复路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void recoveryWithDeletedWorkIsLost() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toDispatched(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** terminal cleanup 路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void terminalCleanupWithDeletedWorkIsLost() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toRunning(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.succeed(
                ToolProcessorTestSupport.successResult("call-1", new TextToolContent("ok")),
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

  /** 同一 JVM 内 recovery：新 token supersede 旧本地 execution 后按 durable DISPATCHING 恢复 UNKNOWN。 */
  @Test
  void sameJvmRecoverySupersedesStaleLocalExecution() {
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
                fixture.store,
                fixture.toolInvocationId,
                ToolProcessorTestSupport.NOW,
                "token-old")));
    fixture.clock.advance(Duration.ofSeconds(61));
    ToolProcessorTestSupport.replaceToolWork(fixture, ToolProcessorTestSupport.NOW);
    ClaimedWork recovered =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, fixture.clock.instant(), "token-new");

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals(1, fixture.gateway.startCalls);
    assertTrue(handle.isCancelled());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** active RUNNING + 伪造不同 token 的 claim：Work-only 前置校验先拦截，合法 execution 不被 cancel。 */
  @Test
  void forgedDifferentTokenWhileActiveRunningIsLostWithoutCancelling() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
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
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(1, fixture.gateway.startCalls);
    assertFalse(handle.isCancelled());
    assertTrue(fixture.processor.hasActiveExecution());
    assertEquals(
        "token-" + fixture.toolInvocationId,
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).leaseToken());
  }

  /** 顺序重复投递同一 claim（同 token）：LOST no-op，不 cancel、不 mutation。 */
  @Test
  void duplicateSequentialProcessWithSameClaimIsLostNoOp() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    long revision =
        ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        revision,
        ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(handle.isCancelled());
    assertEquals(1, fixture.gateway.startCalls);
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
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
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
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
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
    assertEquals(1, fixture.gateway.startCalls);
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertFalse(handle.isCancelled());
  }

  /** 并发不同 token：新 claim 抢占 guard，supersede 旧本地 execution 后恢复 UNKNOWN；旧 Started handle 被 cancel。 */
  @Test
  void concurrentNewTokenPreemptsGuardAndRecoversUnknown() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
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
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
    ClaimedWork claimedA =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW, "token-A");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixture.processor.process(claimedA)));
    threadA.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS));

    ToolProcessorTestSupport.replaceToolWork(fixture, ToolProcessorTestSupport.NOW);
    ClaimedWork claimedB =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW, "token-B");

    AtomicReference<ProcessResult> resultB = new AtomicReference<>();
    Thread threadB = new Thread(() -> resultB.set(fixture.processor.process(claimedB)));
    threadB.start();
    threadB.join(5000);
    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.TERMINATED, resultB.get());
    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(1, fixture.gateway.startCalls);
    assertTrue(handle.isCancelled());
    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** claim lease 已过期：typed LOST_OWNERSHIP，无任何 mutation。 */
  @Test
  void expiredClaimIsLostOwnershipWithoutMutation() {
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** Stop deleteWork 后：claim 行缺失同样 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void deletedWorkClaimIsLostOwnershipWithoutMutation() {
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** close 后拒绝新 process；close 幂等且不 shutdown 注入的 scheduler。 */
  @Test
  void closedProcessorRejectsProcessAndCloseIsIdempotent() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
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

  /** close 在 registry 插入之后执行：Started handle 经 attachHandle 竞态被锁外 cancel，不留新 execution。 */
  @Test
  void closeAfterRegistryInsertCancelsHandleLeavesDispatching() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.beforeStartReturn = listener -> fixture.processor.close();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(1, fixture.gateway.startCalls);
    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        "token-" + fixture.toolInvocationId,
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).leaseToken());
  }

  /**
   * close 在 process 通过入口检查之后、registry 插入之前完成：putIfAbsent 后的 closed 检查必须拦截——abandon 新 execution、 把仍
   * owned 的 DISPATCHING 安全 bounce 回 READY + reschedule，绝不启动 Gateway。
   */
  @Test
  void closeBeforeRegistryInsertBouncesReadyWithoutStartingGateway() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
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
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertNotNull(toolWork);
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        toolWork.availableAt());
    assertNull(toolWork.leaseToken());
  }

  /** close / 本地 cancel 在 heartbeat 启动窗口内获胜：abandoned 检查拦截，不启动 Gateway，DISPATCHING 安全 bounce。 */
  @Test
  void abandonDuringHeartbeatStartupBouncesWithoutStartingGateway() {
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
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        toolWork.availableAt());
    assertNull(toolWork.leaseToken());
  }

  /** scheduler 拒绝 heartbeat 且期间 ownership 已丢：按 bounce 实际结果返回 LOST_OWNERSHIP，不调用 Gateway。 */
  @Test
  void heartbeatSchedulerRejectionWithLostWorkReturnsLostOwnership() {
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
            hooked);
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(claimed));

    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        0, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(processor.hasActiveExecution());
  }

  /** cancel 只关闭本地执行（handle + heartbeat），不反写任何 durable 状态。 */
  @Test
  void cancelStopsLocalExecutionWithoutDurableWrite() {
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
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    assertTrue(fixture.processor.cancel(fixture.toolInvocationId));
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));
    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("late")));
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertTrue(fixture.sink.events.isEmpty());

    assertFalse(fixture.processor.cancel(fixture.toolInvocationId));
    assertThrows(NullPointerException.class, () -> fixture.processor.cancel(null));
  }

  /** close 取消全部本地 execution；durable 行保持 RUNNING 等待 lease 恢复。 */
  @Test
  void closeCancelsAllLocalExecutions() {
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
    ToolProcessorTestSupport.FakeHandle firstHandle = new ToolProcessorTestSupport.FakeHandle();
    ToolProcessorTestSupport.FakeHandle secondHandle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(firstHandle));
    fixture.gateway.queueStart(new ToolGateway.Started(secondHandle));
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

    fixture.processor.close();

    assertFalse(fixture.processor.hasActiveExecution());
    assertTrue(firstHandle.isCancelled());
    assertTrue(secondHandle.isCancelled());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, second.toolInvocationId()).status());
  }

  /** ToolProcessorConfig：两个 delay 必须为正且至少 1ms，其余参数非空。 */
  @Test
  void toolProcessorConfigValidates() {
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
                fixture.scheduler));
    new ToolProcessorConfig(
        ToolProcessorTestSupport.LEASE_CONFIG,
        () -> ToolProcessorTestSupport.NO_RETRY,
        ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
        ToolProcessorTestSupport.BUSY_FALLBACK_DELAY);
  }

  /** scheduler 拒绝 heartbeat 提交：视为无法维持 lease，bounce 为 RESCHEDULED 且不调用 Gateway。 */
  @Test
  void heartbeatSchedulerRejectionBouncesWithoutGatewayCall() {
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
            dead);
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        deadProcessor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** WAITING_APPROVAL 的 ownership 已丢失：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void waitingApprovalWithLostWorkIsLost() {
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** WAITING_APPROVAL 被 Stop 为 CANCELLED 后：terminal cleanup 只确保 THREAD wake 后 complete。 */
  @Test
  void cancelledWaitingApprovalCleanupWakesThread() {
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
        0, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** 前置状态为 DENIED 决策（FAILED）：terminal cleanup 同样只确保 THREAD wake 后 complete。 */
  @Test
  void deniedTerminalCleanupWakesThread() {
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
   * 本地 cancel 在 Started 的 markRunning 事务期间获胜：markRunning 仍 commit（durable RUNNING），但 activate 观察到
   * abandoned 后必须返回 LOST 且绝不调用 {@code handle.activate()}，并由 abandon best-effort cancel handle；lease
   * 到期后由 dispatcher 恢复 UNKNOWN。
   */
  @Test
  void cancelWinningDuringMarkRunningReturnsLostAndCancelsHandle() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    CountDownLatch inStart = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    fixture.gateway.beforeStartReturn =
        listener -> {
          inStart.countDown();
          try {
            proceed.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queueStart(new ToolGateway.Started(handle));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread processThread =
        new Thread(
            () ->
                result.set(
                    fixture.processor.process(
                        ToolProcessorTestSupport.claim(
                            fixture.store,
                            fixture.toolInvocationId,
                            ToolProcessorTestSupport.NOW))));
    processThread.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS), "process must be inside gateway.start");

    // 测试线程抢 store monitor：让 process 的 markRunning 事务阻塞在其上。
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch releaseMonitor = new CountDownLatch(1);
    Thread holdStore =
        new Thread(
            () ->
                fixture.store.transaction(
                    ignored -> {
                      holding.countDown();
                      try {
                        releaseMonitor.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    holdStore.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS));

    proceed.countDown();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (processThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(
        System.nanoTime() < deadline, "process must block on the store monitor (markRunning)");
    assertTrue(fixture.processor.cancel(fixture.toolInvocationId));

    releaseMonitor.countDown();
    holdStore.join(5000);
    processThread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(0, handle.activates.get(), "abandon 先获胜时 handle.activate 绝不调用");
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /**
   * 本地 cancel 在 handle.activate 已经开始之后获胜：abandon 必须推迟 handle cancel 直到 activate 返回，外部调用序只能是
   * ACTIVATE -&gt; CANCEL，cancel 绝不丢失。
   */
  @Test
  void cancelWinningDuringActivationDefersCancelUntilActivateReturns() throws Exception {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    List<String> order = new CopyOnWriteArrayList<>();
    CountDownLatch inActivate = new CountDownLatch(1);
    CountDownLatch releaseActivate = new CountDownLatch(1);
    ToolGateway.Handle blockingHandle =
        new ToolGateway.Handle() {
          @Override
          public void cancel() {
            order.add("cancel");
          }

          @Override
          public void activate() {
            order.add("activate");
            inActivate.countDown();
            try {
              releaseActivate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
          }
        };
    fixture.gateway.queueStart(new ToolGateway.Started(blockingHandle));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread processThread =
        new Thread(
            () ->
                result.set(
                    fixture.processor.process(
                        ToolProcessorTestSupport.claim(
                            fixture.store,
                            fixture.toolInvocationId,
                            ToolProcessorTestSupport.NOW))));
    processThread.start();
    assertTrue(inActivate.await(5, TimeUnit.SECONDS), "handle.activate must have begun");

    assertTrue(fixture.processor.cancel(fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(List.of("activate"), order, "activation 中的 abandon 必须推迟 cancel");

    releaseActivate.countDown();
    processThread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(List.of("activate", "cancel"), order, "外部调用序必须保持 ACTIVATE -> CANCEL");
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
  }

  /**
   * 本地 cancel 与 Stop deleteWork 同时在 heartbeat 启动窗口内获胜：不启动 Gateway；bounce 因 work 已删失败，按实际 结果返回
   * LOST_OWNERSHIP（不得无条件 RESCHEDULED）。
   */
  @Test
  void cancelWithLostWorkDuringHeartbeatStartupReturnsLost() {
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
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** abandon 幂等：第二次调用不再取消 handle、不再通知 owner。 */
  @Test
  void abandonIsIdempotentAndReleasesOwnerOnce() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.toDispatched(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
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
            ignored -> releases.incrementAndGet());
    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    execution.abandon();
    execution.abandon();

    assertTrue(execution.abandoned());
    assertEquals(1, releases.get());
    assertTrue(handle.isCancelled());
  }
}
