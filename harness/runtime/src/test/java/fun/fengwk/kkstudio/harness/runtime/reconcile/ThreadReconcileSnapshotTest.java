package fun.fengwk.kkstudio.harness.runtime.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.kernel.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.kernel.thread.InputStatus;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;

import java.util.List;
import java.util.Optional;

/**
 * {@link ThreadReconcileSnapshot} 构造约束：ownership / thread 一致性、primary debt/action 互斥、 阻塞 owner 与
 * kind、plan head 一致性、queued inputs 严格递增等不变量。
 */
class ThreadReconcileSnapshotTest {

  @Test
  void constructsWithEmptyPrimaryActions() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");

    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(
            ownership,
            thread,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty());

    assertEquals(ownership, snapshot.ownership());
    assertEquals(thread, snapshot.thread());
  }

  @Test
  void constructsWhenOnlyTerminalModelIdIsPresent() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(
            ownership,
            thread,
            Optional.of(99L),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty());
    assertEquals(Optional.of(99L), snapshot.terminalModelInvocationId());
  }

  @Test
  void rejectsOwnershipWithDifferentThreadId() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(2L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.empty()));
  }

  @Test
  void rejectsOwnershipWithDifferentEpoch() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 1L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.empty()));
  }

  @Test
  void rejectsOwnershipWithDifferentProcessorToken() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok-a");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok-b");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.empty()));
  }

  @Test
  void rejectsNonPositiveTerminalModelId() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.of(0L),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.empty()));
  }

  @Test
  void rejectsNonPositiveReadyToolAssistantId() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.of(-1L),
                Optional.empty(),
                List.of(),
                Optional.empty()));
  }

  @Test
  void rejectsBlockerOwnerThatIsNotOwnershipThread() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ContinuationRef wrongOwner =
        new ContinuationRef(
            new ExecutionTarget(ExecutionTargetKind.THREAD, 999L),
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.of(wrongOwner),
                List.of(),
                Optional.empty()));
  }

  @Test
  void rejectsBlockerWithThreadKind() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ContinuationRef threadBlocker =
        new ContinuationRef(
            ownership.threadTarget(), new ExecutionTarget(ExecutionTargetKind.THREAD, 9L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.of(threadBlocker),
                List.of(),
                Optional.empty()));
  }

  @Test
  void acceptsBlockerWithModelInvocationKind() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ContinuationRef blocker =
        new ContinuationRef(
            ownership.threadTarget(),
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L));
    ThreadReconcileSnapshot snapshot =
        new ThreadReconcileSnapshot(
            ownership,
            thread,
            Optional.empty(),
            Optional.empty(),
            Optional.of(blocker),
            List.of(),
            Optional.empty());
    assertEquals(Optional.of(blocker), snapshot.blockerContinuation());
  }

  @Test
  void rejectsPlanWithMismatchedHead() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok", 1L);
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            999L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(plan)));
  }

  @Test
  void rejectsMultiplePrimaryDebts() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok", 1L);
    ModelInvocationPlan plan =
        new ModelInvocationPlan(
            1L, ReconcileTestSupport.providerRequest(), ReconcileTestSupport.configSnapshot());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.of(7L),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(plan)));
  }

  @Test
  void rejectsQueuedInputsWithDifferentThreadId() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    List<ThreadInput> inputs =
        List.of(ReconcileTestSupport.input(2L, 1L, ThreadInputType.USER_MESSAGE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                inputs,
                Optional.empty()));
  }

  @Test
  void rejectsQueuedInputsThatAreNotQueued() {
    ThreadOwnership ownership = ReconcileTestSupport.ownership(1L, 0L, "tok");
    HarnessThread thread = ReconcileTestSupport.thread(1L, 0L, "tok");
    ThreadInput applied = appliedInput();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(applied),
                Optional.empty()));
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
        () ->
            new ThreadReconcileSnapshot(
                ownership,
                thread,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                inputs,
                Optional.empty()));
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
