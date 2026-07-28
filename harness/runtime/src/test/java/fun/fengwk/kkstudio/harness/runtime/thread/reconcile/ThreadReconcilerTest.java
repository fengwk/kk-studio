package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.Failure;
import fun.fengwk.kkstudio.harness.runtime.execution.StepResult;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ThreadReconciler 行为与优先级合约测试：覆盖 precedence、response-debt end-to-end、config-only / config+message
 * 边界、blocker terminal race、enqueue/quiesce race、claim/renew/load/write 所有权 丢失、Model creation
 * failure、Clock 单调推进与 max-step safety。
 */
class ThreadReconcilerTest {

  private static final long THREAD_ID = 1L;
  private static final String TOKEN = "token-1";
  private static final Clock CLOCK = Clock.fixed(ReconcileTestSupport.NOW, ZoneOffset.UTC);

  // --------------------- 构造约束 ---------------------

  @Test
  void rejectsNonPositiveThreadId() {
    FakeTransactions txs = new FakeTransactions();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconciler(txs, CLOCK).reconcile(0L, TOKEN));
  }

  @Test
  void rejectsNullToken() {
    FakeTransactions txs = new FakeTransactions();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, null));
  }

  @Test
  void rejectsBlankToken() {
    FakeTransactions txs = new FakeTransactions();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, " "));
  }

  @Test
  void rejectsNonPositiveMaxSteps() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconciler(new FakeTransactions(), CLOCK, 0));
  }

  @Test
  void rejectsNullTransactions() {
    assertThrows(NullPointerException.class, () -> new ThreadReconciler(null));
  }

  @Test
  void rejectsNullClock() {
    assertThrows(
        NullPointerException.class, () -> new ThreadReconciler(new FakeTransactions(), null));
  }

  @Test
  void defaultConstructorUsesRuntimeClock() {
    FakeTransactions txs = new FakeTransactions();
    txs.claimOutcome = Optional.empty();

    assertInstanceOf(
        StepResult.LostOwnership.class, new ThreadReconciler(txs).reconcile(THREAD_ID, TOKEN));
  }

  // --------------------- claim 阶段 ---------------------

  @Test
  void returnsLostOwnershipWhenClaimFails() {
    FakeTransactions txs = new FakeTransactions();
    txs.claimOutcome = Optional.empty();

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.renewCount.get());
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void rejectsClaimForDifferentThreadAfterBestEffortRelease() {
    FakeTransactions txs = new FakeTransactions();
    txs.claimOutcome = Optional.of(ReconcileTestSupport.ownership(2L, 0L, TOKEN));

    assertThrows(
        IllegalStateException.class,
        () -> new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN));
    assertEquals(1, txs.bestEffortReleaseCount.get());
  }

  @Test
  void rejectsClaimForDifferentTokenAfterBestEffortRelease() {
    FakeTransactions txs = new FakeTransactions();
    txs.claimOutcome = Optional.of(ReconcileTestSupport.ownership(THREAD_ID, 0L, "other-token"));

    assertThrows(
        IllegalStateException.class,
        () -> new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN));
    assertEquals(1, txs.bestEffortReleaseCount.get());
  }

  @Test
  void rejectsSnapshotForDifferentOwnershipAfterBestEffortRelease() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshotFor(2L, "other-token", b -> {});

    assertThrows(
        IllegalStateException.class,
        () -> new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN));
    assertEquals(1, txs.bestEffortReleaseCount.get());
  }

  // --------------------- 1) terminal Model apply 优先级 ---------------------

  @Test
  void terminalModelApplyRunsBeforeToolApply() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> b.terminalModelId = Optional.of(11L));
    txs.queueSnapshot(b -> b.readyToolAssistantId = Optional.of(22L));
    txs.queueSnapshot(b -> {});
    txs.applyTerminalModelOutcome = ApplyOutcome.PROGRESSED;
    txs.applyTerminalToolOutcome = ApplyOutcome.PROGRESSED;
    txs.quiesceOutcome = QuiesceOutcome.QUIESCENT;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    assertInstanceOf(StepResult.Quiescent.class, result);
    assertEquals(1, txs.applyTerminalModelCount.get());
    assertEquals(1, txs.applyTerminalToolCount.get());
    assertEquals(List.of("apply-model", "apply-tools", "quiesce"), txs.operationLog);
    assertEquals(0, txs.suspendCount.get());
    assertEquals(0, txs.createCount.get());
    assertEquals(0, txs.harvestCount.get());
  }

  @Test
  void terminalToolApplyRunsBeforeSuspend() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> b.readyToolAssistantId = Optional.of(22L));
    txs.queueSnapshot(b -> {});
    txs.applyTerminalToolOutcome = ApplyOutcome.PROGRESSED;
    txs.quiesceOutcome = QuiesceOutcome.QUIESCENT;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    assertInstanceOf(StepResult.Quiescent.class, result);
    assertEquals(1, txs.applyTerminalToolCount.get());
    assertEquals(0, txs.suspendCount.get());
    assertEquals(0, txs.createCount.get());
  }

  @Test
  void suspendReturnsExactExpectedContinuation() {
    // Snapshot 暴露的 expected blocker 必须原样传给事务；SUSPENDED 没有携带替代 blocker 的通道。
    FakeTransactions txs = new FakeTransactions();
    ContinuationRef blocker =
        new ContinuationRef(
            new ExecutionTarget(ExecutionTargetKind.THREAD, THREAD_ID),
            new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 33L));
    txs.queueSnapshot(b -> b.blockerContinuation = Optional.of(blocker));
    txs.suspendOutcome = SuspendOutcome.SUSPENDED;

    StepResult.Suspended result =
        assertInstanceOf(
            StepResult.Suspended.class,
            new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN));
    assertSame(blocker, result.continuation());
    assertSame(blocker, txs.lastExpectedBlocker.get(), "transaction receives snapshot blocker");
  }

  @Test
  void createModelInvocationRunsBeforeHarvest() {
    FakeTransactions txs = new FakeTransactions();
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            1L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    txs.queueSnapshot(
        b -> {
          b.modelInvocationPlan = Optional.of(plan);
          b.queuedInputs.add(
              ReconcileTestSupport.input(THREAD_ID, 1L, ThreadInputType.USER_MESSAGE));
        });
    txs.createOutcome =
        new ModelCreationOutcome.Created(
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 99L));

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    StepResult.Suspended suspended = assertInstanceOf(StepResult.Suspended.class, result);
    assertEquals(
        new ExecutionTarget(ExecutionTargetKind.THREAD, THREAD_ID),
        suspended.continuation().owner());
    assertEquals(
        new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 99L),
        suspended.continuation().blocker());
    assertEquals(1, txs.createCount.get());
    assertEquals(0, txs.harvestCount.get());
  }

  @Test
  void harvestRunsBeforeQuiesce() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(
        b ->
            b.queuedInputs.add(
                ReconcileTestSupport.input(THREAD_ID, 1L, ThreadInputType.SET_YOLO)));
    txs.queueSnapshot(b -> {});
    txs.harvestOutcome = ApplyOutcome.PROGRESSED;
    txs.quiesceOutcome = QuiesceOutcome.QUIESCENT;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    assertInstanceOf(StepResult.Quiescent.class, result);
    assertEquals(1, txs.harvestCount.get());
    assertEquals(1, txs.quiesceCount.get());
  }

  // --------------------- config-only 与 config+message 路径 ---------------------

  @Test
  void configOnlyBoundaryAdvancesAndThenQuiesces() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(
        b ->
            b.queuedInputs.addAll(
                List.of(
                    ReconcileTestSupport.input(THREAD_ID, 1L, ThreadInputType.SET_MODEL),
                    ReconcileTestSupport.input(THREAD_ID, 2L, ThreadInputType.SET_AGENT))));
    txs.queueSnapshot(b -> {});
    txs.harvestOutcome = ApplyOutcome.PROGRESSED;
    txs.quiesceOutcome = QuiesceOutcome.QUIESCENT;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    assertInstanceOf(StepResult.Quiescent.class, result);
    assertEquals(1, txs.harvestCount.get());
    TurnBoundary harvested = txs.lastHarvestedBoundary.get();
    assertNotNull(harvested);
    assertFalse(harvested.hasMessage());
    assertEquals(2L, harvested.lastSequence());
  }

  @Test
  void configPlusMessageBoundarySelectsUpToOneMessage() {
    FakeTransactions txs = new FakeTransactions();
    ThreadInput config = ReconcileTestSupport.input(THREAD_ID, 1L, ThreadInputType.SET_MODEL);
    ThreadInput message = ReconcileTestSupport.input(THREAD_ID, 2L, ThreadInputType.USER_MESSAGE);
    ThreadInput later = ReconcileTestSupport.input(THREAD_ID, 3L, ThreadInputType.USER_MESSAGE);
    txs.queueSnapshot(b -> b.queuedInputs.addAll(List.of(config, message, later)));
    txs.harvestOutcome = ApplyOutcome.PROGRESSED;
    txs.queueSnapshot(
        b -> {
          b.headEntryId = 2L;
          b.modelInvocationPlan =
              Optional.of(
                  new ModelInvocationPlan(
                      2L,
                      ReconcileTestSupport.providerRequest(),
                      ReconcileTestSupport.configSnapshot()));
          b.queuedInputs.add(later);
        });
    txs.createOutcome =
        new ModelCreationOutcome.Created(
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 9L));

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    assertInstanceOf(StepResult.Suspended.class, result);
    TurnBoundary harvested = txs.lastHarvestedBoundary.get();
    assertNotNull(harvested);
    assertTrue(harvested.hasMessage());
    assertEquals(2L, harvested.lastSequence());
    assertEquals(1, txs.createCount.get());
  }

  // --------------------- response-debt end-to-end ---------------------

  @Test
  void responseDebtEndToEndCreatesInvocationBeforeNextInputIsHarvested() {
    // 端到端控制流：
    // snapshot 1 包含 config+message boundary 与一条后续 queued input；
    // harvest 成功后，snapshot 2 暴露 ModelInvocationPlan 并仍包含后续 input；
    // create 成功后返回 Suspended；后续 input 不会被 harvest。
    FakeTransactions txs = new FakeTransactions();
    ThreadInput cfg = ReconcileTestSupport.input(THREAD_ID, 1L, ThreadInputType.SET_MODEL);
    ThreadInput msg = ReconcileTestSupport.input(THREAD_ID, 2L, ThreadInputType.USER_MESSAGE);
    ThreadInput later = ReconcileTestSupport.input(THREAD_ID, 3L, ThreadInputType.USER_MESSAGE);

    txs.queueSnapshot(b -> b.queuedInputs.addAll(List.of(cfg, msg, later)));
    txs.harvestOutcome = ApplyOutcome.PROGRESSED;

    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            2L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    txs.queueSnapshot(
        b -> {
          b.headEntryId = 2L;
          b.modelInvocationPlan = Optional.of(plan);
          b.queuedInputs.add(later);
        });
    txs.createOutcome =
        new ModelCreationOutcome.Created(
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 17L));

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    StepResult.Suspended suspended = assertInstanceOf(StepResult.Suspended.class, result);
    assertEquals(
        new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 17L),
        suspended.continuation().blocker());

    assertEquals(1, txs.harvestCount.get(), "exactly one boundary harvested");
    TurnBoundary harvested = txs.lastHarvestedBoundary.get();
    assertEquals(2L, harvested.lastSequence(), "boundary ends at the first message sequence");
    assertEquals(1, harvested.configInputs().size());
    assertSame(msg, harvested.messageInput().orElseThrow());

    assertEquals(1, txs.createCount.get(), "ModelInvocation created after the boundary");
    assertEquals(0, txs.quiesceCount.get(), "later queued input must not be quiesced");
  }

  // --------------------- blocker terminal race ---------------------

  @Test
  void blockerTerminalRaceLoopsWithoutReleasingLease() {
    // suspend 路径检测到 sibling 终态化（WORK_AVAILABLE），继续循环并最终 quiesce。
    FakeTransactions txs = new FakeTransactions();
    ContinuationRef blocker =
        new ContinuationRef(
            new ExecutionTarget(ExecutionTargetKind.THREAD, THREAD_ID),
            new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 33L));

    txs.queueSnapshot(b -> b.blockerContinuation = Optional.of(blocker));
    txs.queueSnapshot(b -> {});
    txs.suspendOutcome = SuspendOutcome.WORK_AVAILABLE;
    txs.quiesceOutcome = QuiesceOutcome.QUIESCENT;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    assertInstanceOf(StepResult.Quiescent.class, result);
    assertEquals(1, txs.suspendCount.get());
    assertEquals(0, txs.bestEffortReleaseCount.get(), "WORK_AVAILABLE must not release lease");
    assertEquals(1, txs.quiesceCount.get());
    assertSame(blocker, txs.lastExpectedBlocker.get());
  }

  @Test
  void enqueueDuringQuiesceRaceLoopsAndThenQuiesces() {
    FakeTransactions txs = new FakeTransactions();
    // 首次 quiesce 发现新 enqueue，循环回到 quiesce 路径后真正 quiesce。
    txs.queueSnapshot(b -> {});
    txs.queueSnapshot(b -> {});
    txs.queueQuiesceOutcome(QuiesceOutcome.WORK_AVAILABLE);
    txs.queueQuiesceOutcome(QuiesceOutcome.QUIESCENT);

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);

    assertInstanceOf(StepResult.Quiescent.class, result);
    assertEquals(2, txs.quiesceCount.get());
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  // --------------------- claim / renew / load / write 所有权丢失 ---------------------

  @Test
  void lostOwnershipOnRenewReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> {});
    txs.renewOutcome = false;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void lostOwnershipOnSnapshotLoadReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueEmptySnapshot();

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void lostOwnershipOnApplyReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> b.terminalModelId = Optional.of(99L));
    txs.applyTerminalModelOutcome = ApplyOutcome.LOST_OWNERSHIP;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void lostOwnershipOnToolApplyReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> b.readyToolAssistantId = Optional.of(33L));
    txs.applyTerminalToolOutcome = ApplyOutcome.LOST_OWNERSHIP;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void lostOwnershipOnCreateReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            1L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    txs.queueSnapshot(b -> b.modelInvocationPlan = Optional.of(plan));
    txs.createOutcome = new ModelCreationOutcome.LostOwnership();

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void lostOwnershipOnSuspendReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    ContinuationRef blocker =
        new ContinuationRef(
            new ExecutionTarget(ExecutionTargetKind.THREAD, THREAD_ID),
            new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 33L));
    txs.queueSnapshot(b -> b.blockerContinuation = Optional.of(blocker));
    txs.suspendOutcome = SuspendOutcome.LOST_OWNERSHIP;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void lostOwnershipOnQuiesceReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> {});
    txs.quiesceOutcome = QuiesceOutcome.LOST_OWNERSHIP;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  @Test
  void lostOwnershipOnHarvestReturnsLostOwnership() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(
        b ->
            b.queuedInputs.add(
                ReconcileTestSupport.input(THREAD_ID, 1L, ThreadInputType.USER_MESSAGE)));
    txs.harvestOutcome = ApplyOutcome.LOST_OWNERSHIP;

    StepResult result = new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN);
    assertInstanceOf(StepResult.LostOwnership.class, result);
    assertEquals(0, txs.bestEffortReleaseCount.get());
  }

  // --------------------- 仅 creation 保留 typed failure ---------------------

  @Test
  void createFailureReleasesLeaseAndReturnsFailed() {
    FakeTransactions txs = new FakeTransactions();
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            1L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    Failure failure = new Failure("CREATE_FAIL", "boom");
    txs.queueSnapshot(b -> b.modelInvocationPlan = Optional.of(plan));
    txs.createOutcome = new ModelCreationOutcome.Failed(failure);

    StepResult.Failed result =
        assertInstanceOf(
            StepResult.Failed.class, new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN));
    assertSame(failure, result.failure());
    assertEquals(1, txs.bestEffortReleaseCount.get());
  }

  // --------------------- 意外 RuntimeException ---------------------

  @Test
  void unexpectedRuntimeExceptionReleasesLeaseAndRethrows() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> {});
    txs.renewBehavior =
        () -> {
          throw new IllegalStateException("boom");
        };

    assertThrows(
        IllegalStateException.class,
        () -> new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN));
    assertEquals(1, txs.bestEffortReleaseCount.get());
  }

  @Test
  void releaseFailureDoesNotMaskUnexpectedRuntimeException() {
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> {});
    txs.renewBehavior =
        () -> {
          throw new IllegalStateException("primary");
        };
    txs.releaseBehavior =
        () -> {
          throw new IllegalStateException("cleanup");
        };

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> new ThreadReconciler(txs, CLOCK).reconcile(THREAD_ID, TOKEN));
    assertEquals("primary", thrown.getMessage());
    assertEquals(1, txs.bestEffortReleaseCount.get());
  }

  // --------------------- max-step safety ---------------------

  @Test
  void exceededStepLimitReturnsFailedAfterBestEffortRelease() {
    // 每次 snapshot 只出现一条新到达的 config，连续两次 harvest 后触发 step 上限。
    FakeTransactions txs = new FakeTransactions();
    txs.harvestOutcome = ApplyOutcome.PROGRESSED;
    for (int sequence = 1; sequence <= 2; sequence++) {
      int currentSequence = sequence;
      txs.queueSnapshot(
          b ->
              b.queuedInputs.add(
                  ReconcileTestSupport.input(
                      THREAD_ID, currentSequence, ThreadInputType.SET_YOLO)));
    }

    ThreadReconciler reconciler = new ThreadReconciler(txs, CLOCK, 2);
    StepResult.Failed result =
        assertInstanceOf(StepResult.Failed.class, reconciler.reconcile(THREAD_ID, TOKEN));
    assertEquals("RECONCILE_STEP_LIMIT", result.failure().code());
    assertEquals(1, txs.bestEffortReleaseCount.get());
  }

  // --------------------- Clock 单调推进 ---------------------

  @Test
  void clockAdvancesBetweenClaimRenewLoadAndMutations() {
    ReconcileTestSupport.MutableClock localClock =
        new ReconcileTestSupport.MutableClock(ReconcileTestSupport.NOW, Duration.ofMillis(5));
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> {});
    txs.quiesceOutcome = QuiesceOutcome.QUIESCENT;

    new ThreadReconciler(txs, localClock).reconcile(THREAD_ID, TOKEN);

    // claim -> renew -> load -> quiesce 共 4 次 instant() 调用，Clock 应给出递增序列。
    assertEquals(4, txs.instantLog.size());
    for (int i = 1; i < txs.instantLog.size(); i++) {
      assertTrue(
          txs.instantLog.get(i).isAfter(txs.instantLog.get(i - 1)),
          "later mutations must receive later instants: " + txs.instantLog);
    }
  }

  @Test
  void renewalAndMutationObservationsAreDistinctInstants() {
    ReconcileTestSupport.MutableClock localClock =
        new ReconcileTestSupport.MutableClock(ReconcileTestSupport.NOW, Duration.ofMillis(7));
    FakeTransactions txs = new FakeTransactions();
    txs.queueSnapshot(b -> b.terminalModelId = Optional.of(7L));
    txs.queueSnapshot(b -> {});
    txs.applyTerminalModelOutcome = ApplyOutcome.PROGRESSED;
    txs.quiesceOutcome = QuiesceOutcome.QUIESCENT;

    new ThreadReconciler(txs, localClock).reconcile(THREAD_ID, TOKEN);

    // claim + (renew + load + applyModel) + (renew + load + quiesce) = 7 个调用。
    assertEquals(7, txs.instantLog.size());
    List<Instant> unique = new ArrayList<>(new LinkedHashSet<>(txs.instantLog));
    assertEquals(
        txs.instantLog.size(), unique.size(), "every mutation must observe a distinct instant");
  }

  // --------------------- 工具方法 ---------------------

  private static final class SnapshotBuilder {
    long threadId;
    String token;
    long headEntryId = 1L;
    Optional<Long> terminalModelId = Optional.empty();
    Optional<Long> readyToolAssistantId = Optional.empty();
    Optional<ContinuationRef> blockerContinuation = Optional.empty();
    List<ThreadInput> queuedInputs = new ArrayList<>();
    Optional<ModelInvocationPlan> modelInvocationPlan = Optional.empty();

    SnapshotBuilder(long threadId, String token) {
      this.threadId = threadId;
      this.token = token;
    }

    ThreadReconcileSnapshot build() {
      ThreadOwnership ownership = ReconcileTestSupport.ownership(threadId, 0L, token);
      return new ThreadReconcileSnapshot(
          ownership,
          ReconcileTestSupport.thread(threadId, 0L, token, headEntryId),
          terminalModelId,
          readyToolAssistantId,
          blockerContinuation,
          queuedInputs,
          modelInvocationPlan);
    }
  }

  @FunctionalInterface
  private interface SnapshotMutator {
    void mutate(SnapshotBuilder builder);
  }

  /** 编程式 fake：每次调用返回预先配置的 outcome；renew/snapshot 列表默认成功推进；记录每次 {@code now} 参数，便于断言 Clock 单调推进。 */
  private static final class FakeTransactions implements ThreadReconcileTransactions {

    Optional<ThreadOwnership> claimOutcome =
        Optional.of(ReconcileTestSupport.ownership(THREAD_ID, 0L, TOKEN));
    boolean renewOutcome = true;
    Deque<Optional<ThreadReconcileSnapshot>> snapshots = new ArrayDeque<>();
    Runnable renewBehavior = () -> {};
    Runnable releaseBehavior = () -> {};

    ApplyOutcome applyTerminalModelOutcome;
    ApplyOutcome applyTerminalToolOutcome;
    SuspendOutcome suspendOutcome;
    ApplyOutcome harvestOutcome;
    ModelCreationOutcome createOutcome;
    QuiesceOutcome quiesceOutcome;
    Deque<QuiesceOutcome> quiesceOutcomes = new ArrayDeque<>();

    final List<Instant> instantLog = new ArrayList<>();
    final List<String> operationLog = new ArrayList<>();
    final AtomicInteger renewCount = new AtomicInteger();
    final AtomicInteger applyTerminalModelCount = new AtomicInteger();
    final AtomicInteger applyTerminalToolCount = new AtomicInteger();
    final AtomicInteger suspendCount = new AtomicInteger();
    final AtomicInteger createCount = new AtomicInteger();
    final AtomicInteger harvestCount = new AtomicInteger();
    final AtomicInteger quiesceCount = new AtomicInteger();
    final AtomicInteger bestEffortReleaseCount = new AtomicInteger();
    final AtomicReference<TurnBoundary> lastHarvestedBoundary = new AtomicReference<>();
    final AtomicReference<ContinuationRef> lastExpectedBlocker = new AtomicReference<>();

    void queueSnapshot(SnapshotMutator mutator) {
      queueSnapshotFor(THREAD_ID, TOKEN, mutator);
    }

    void queueSnapshotFor(long threadId, String token, SnapshotMutator mutator) {
      SnapshotBuilder builder = new SnapshotBuilder(threadId, token);
      mutator.mutate(builder);
      snapshots.add(Optional.of(builder.build()));
    }

    void queueEmptySnapshot() {
      snapshots.add(Optional.empty());
    }

    void queueQuiesceOutcome(QuiesceOutcome outcome) {
      quiesceOutcomes.add(outcome);
    }

    @Override
    public Optional<ThreadOwnership> claim(long threadId, String processorToken, Instant now) {
      instantLog.add(now);
      return claimOutcome;
    }

    @Override
    public boolean renew(ThreadOwnership ownership, Instant now) {
      renewCount.incrementAndGet();
      instantLog.add(now);
      renewBehavior.run();
      return renewOutcome;
    }

    @Override
    public Optional<ThreadReconcileSnapshot> loadOwnedSnapshot(
        ThreadOwnership ownership, Instant now) {
      instantLog.add(now);
      Optional<ThreadReconcileSnapshot> next = snapshots.poll();
      return next == null ? Optional.empty() : next;
    }

    @Override
    public ApplyOutcome applyTerminalModel(
        ThreadOwnership ownership, long modelInvocationId, Instant now) {
      applyTerminalModelCount.incrementAndGet();
      instantLog.add(now);
      operationLog.add("apply-model");
      return applyTerminalModelOutcome;
    }

    @Override
    public ApplyOutcome applyTerminalToolResults(
        ThreadOwnership ownership, long assistantEntryId, Instant now) {
      applyTerminalToolCount.incrementAndGet();
      instantLog.add(now);
      operationLog.add("apply-tools");
      return applyTerminalToolOutcome;
    }

    @Override
    public SuspendOutcome suspendAndRecheck(
        ThreadOwnership ownership, ContinuationRef expectedBlocker, Instant now) {
      suspendCount.incrementAndGet();
      instantLog.add(now);
      operationLog.add("suspend");
      lastExpectedBlocker.set(expectedBlocker);
      return suspendOutcome;
    }

    @Override
    public ApplyOutcome harvestBoundary(
        ThreadOwnership ownership, TurnBoundary boundary, Instant now) {
      harvestCount.incrementAndGet();
      instantLog.add(now);
      operationLog.add("harvest");
      lastHarvestedBoundary.set(boundary);
      return harvestOutcome;
    }

    @Override
    public ModelCreationOutcome createModelInvocationAndRelease(
        ThreadOwnership ownership, ModelInvocationPlan plan, Instant now) {
      createCount.incrementAndGet();
      instantLog.add(now);
      operationLog.add("create-model");
      return createOutcome;
    }

    @Override
    public QuiesceOutcome quiesceAndRecheck(ThreadOwnership ownership, Instant now) {
      quiesceCount.incrementAndGet();
      instantLog.add(now);
      operationLog.add("quiesce");
      QuiesceOutcome polled = quiesceOutcomes.poll();
      return polled != null ? polled : quiesceOutcome;
    }

    @Override
    public void bestEffortRelease(ThreadOwnership ownership, Instant now) {
      bestEffortReleaseCount.incrementAndGet();
      instantLog.add(now);
      releaseBehavior.run();
    }
  }
}
