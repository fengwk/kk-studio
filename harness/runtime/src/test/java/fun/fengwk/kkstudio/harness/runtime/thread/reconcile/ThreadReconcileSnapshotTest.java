package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.util.List;
import java.util.Optional;

/**
 * {@link ThreadReconcileSnapshot} 构造约束：ownership / thread 一致性、{@link
 * ThreadReconcileSnapshot.PrimaryWork} 四种变体各自正确、blocker owner/kind 不变量、plan head 一致性、queued inputs
 * 严格 递增等不变量的测试。
 */
class ThreadReconcileSnapshotTest {

  @Test
  void constructsWithEmptyPrimaryWork() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");

    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), List.of());

    assertEquals(ownership, snapshot.ownership());
    assertEquals(thread, snapshot.thread());
    assertTrue(snapshot.primaryWork().isEmpty());
  }

  @Test
  void constructsWithApplyTerminalModel() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalModel(99L);

    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of());

    assertTrue(snapshot.primaryWork().isPresent());
    assertInstanceOf(
        ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalModel.class, snapshot.primaryWork().get());
    assertEquals(
        99L,
        ((ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalModel) snapshot.primaryWork().get())
            .modelInvocationId());
  }

  @Test
  void constructsWithApplyTerminalToolBatch() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalToolBatch(55L);

    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of());

    assertTrue(snapshot.primaryWork().isPresent());
    assertInstanceOf(
        ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalToolBatch.class,
        snapshot.primaryWork().get());
    assertEquals(
        55L,
        ((ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalToolBatch) snapshot.primaryWork().get())
            .assistantEntryId());
  }

  @Test
  void constructsWithSuspendForBlocker() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ContinuationRef blocker =
        new ContinuationRef(
            ownership.threadTarget(),
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L));
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker(blocker);

    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of());

    assertTrue(snapshot.primaryWork().isPresent());
    ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker sb =
        assertInstanceOf(
            ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker.class,
            snapshot.primaryWork().get());
    assertEquals(blocker, sb.blocker());
  }

  @Test
  void constructsWithCreateModelInvocation() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok", 1L);
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            1L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation(plan);

    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of());

    assertTrue(snapshot.primaryWork().isPresent());
    ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation cmi =
        assertInstanceOf(
            ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation.class,
            snapshot.primaryWork().get());
    assertEquals(plan, cmi.plan());
  }

  @Test
  void rejectsOwnershipWithDifferentThreadId() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(2L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), List.of()));
  }

  @Test
  void rejectsUnboundOwnedThread() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread =
        new HarnessThread(
            1L,
            null,
            0L,
            true,
            0L,
            new Lease("tok", ReconcileTestSupport.NOW.plusSeconds(60)),
            ReconcileTestSupport.NOW,
            ReconcileTestSupport.NOW);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), List.of()));
  }

  @Test
  void rejectsOwnershipWithDifferentEpoch() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 1L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), List.of()));
  }

  @Test
  void rejectsOwnershipWithDifferentProcessorToken() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok-a");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok-b");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), List.of()));
  }

  @Test
  void rejectsNonPositiveApplyTerminalModelId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalModel(0L));
  }

  @Test
  void rejectsNonPositiveApplyTerminalToolBatchId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalToolBatch(-1L));
  }

  @Test
  void rejectsNullBlocker() {
    assertThrows(
        NullPointerException.class,
        () -> new ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker(null));
  }

  @Test
  void rejectsNullPlan() {
    assertThrows(
        NullPointerException.class,
        () -> new ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation(null));
  }

  @Test
  void rejectsBlockerOwnerThatIsNotOwnershipThread() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ContinuationRef wrongOwner =
        new ContinuationRef(
            new ExecutionTarget(ExecutionTargetKind.THREAD, 999L),
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L));
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker(wrongOwner);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of()));
  }

  @Test
  void rejectsBlockerWithThreadKind() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ContinuationRef threadBlocker =
        new ContinuationRef(
            ownership.threadTarget(), new ExecutionTarget(ExecutionTargetKind.THREAD, 9L));
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker(threadBlocker);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of()));
  }

  @Test
  void acceptsBlockerWithModelInvocationKind() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ContinuationRef blocker =
        new ContinuationRef(
            ownership.threadTarget(),
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L));
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker(blocker);
    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of());

    assertTrue(snapshot.primaryWork().isPresent());
    ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker sb =
        assertInstanceOf(
            ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker.class,
            snapshot.primaryWork().get());
    assertEquals(blocker, sb.blocker());
  }

  @Test
  void rejectsPlanWithMismatchedHead() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok", 1L);
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            999L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation(plan);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of()));
  }

  // 类型系统保证至多一项 PrimaryWork，因此不再需要运行时"至少多于一项"的校验。
  // 以下测试专注于 queuedInputs 不变量。

  @Test
  void rejectsQueuedInputsWithDifferentThreadId() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    List<ThreadInput> inputs =
        List.of(ReconcileTestSupport.input(2L, 1L, ThreadInputType.USER_MESSAGE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), inputs));
  }

  @Test
  void rejectsQueuedInputsThatAreNotQueued() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ThreadInput applied = appliedInput();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), List.of(applied)));
  }

  @Test
  void rejectsNonMonotonicQueuedSequences() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    List<ThreadInput> inputs =
        List.of(
            ReconcileTestSupport.input(1L, 2L, ThreadInputType.SET_MODEL),
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.USER_MESSAGE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), inputs));
  }

  @Test
  void emptyPrimaryWorkCanBeHarvestedOrQuiesced() {
    // 验证 empty primaryWork 时 snapshot 正常构造，可直接用于 harvest/quiesce 路径
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    List<ThreadInput> inputs =
        List.of(ReconcileTestSupport.input(1L, 1L, ThreadInputType.USER_MESSAGE));

    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.empty(), inputs);

    assertTrue(snapshot.primaryWork().isEmpty());
    assertEquals(1, snapshot.queuedInputs().size());
  }

  @Test
  void planPrimaryWorkRequiresMatchingHead() {
    // plan 的 sourceHeadEntryId 必须等于 thread head，否则构造失败
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok", 5L);
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            5L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    ThreadReconcileSnapshot.PrimaryWork work =
        new ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation(plan);
    // 匹配 head：5L == 5L，应成功
    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(ownership, thread, Optional.of(work), List.of());
    assertTrue(snapshot.primaryWork().isPresent());
  }

  private static ThreadInput appliedInput() {
    return new ThreadInput(
        1L,
        1L,
        1L,
        ThreadInputType.USER_MESSAGE,
        () -> ThreadInputType.USER_MESSAGE,
        "applied-key",
        InputStatus.APPLIED,
        ReconcileTestSupport.NOW,
        ReconcileTestSupport.NOW.plusSeconds(1));
  }
}
