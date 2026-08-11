package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T6;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markApprovalNotRequired;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.toolError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** decideToolApproval：allow/deny、精确 replay 幂等性、适用性与 Work 目标。 */
class HarnessRuntimeApprovalTest {

  private InMemoryHarnessStore store;
  private TestClock clock;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    clock = new TestClock(T5);
    runtime = new HarnessRuntime(store, clock);
  }

  private ToolApprovalCommand allow(UUID threadId, UUID toolId) {
    return new ToolApprovalCommand(
        threadId, toolId, ToolApprovalDecision.ALLOWED, TestIds.id(1), "alice", null);
  }

  @Test
  void allowedDecisionResumesAsReadyAndRequestsToolWork() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);

    ToolInvocation decided =
        runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));

    assertEquals(ToolInvocationStatus.READY, decided.status());
    assertEquals(ToolApprovalDecision.ALLOWED, decided.approval().decision());
    assertEquals(TestIds.id(1), decided.approval().decisionId());
    assertEquals("alice", decided.approval().actor());
    assertEquals(T5, decided.approval().decidedAt());
    assertEquals(0, decided.attempt());

    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(decided, stored);

    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    // fixture 已推进一次 head（revision 1），决定再 +1。
    assertEquals(2L, thread.revision());

    Work toolWork =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())).orElseThrow());
    assertEquals(1L, toolWork.wakeVersion());
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())).isEmpty()));
  }

  @Test
  void deniedDecisionTerminatesAsFailedAndRequestsThreadWork() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);

    ToolInvocation decided =
        runtime.decideToolApproval(
            new ToolApprovalCommand(
                baseline.threadId(),
                baseline.toolId(),
                ToolApprovalDecision.DENIED,
                TestIds.id(1),
                "bob",
                "not now"));

    assertEquals(ToolInvocationStatus.FAILED, decided.status());
    assertEquals(ToolApprovalDecision.DENIED, decided.approval().decision());
    assertEquals("DENIED", decided.error().kind());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(2L, thread.revision());
    Work threadWork =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElseThrow());
    assertEquals(1L, threadWork.wakeVersion());
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())).isEmpty()));
  }

  @Test
  void approvalDecisionUsesDurableFloorsWithoutClampingWorkClock() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    ToolInvocation waiting = setWaitingApproval(store, baseline);
    clock.advance(T2);

    ToolInvocation decided =
        runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));

    assertEquals(T3, waiting.approval().requestedAt());
    assertEquals(T3, decided.approval().decidedAt());
    assertEquals(T3, decided.updatedAt());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(T3, thread.updatedAt());
    Work toolWork =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())).orElseThrow());
    assertEquals(T2, toolWork.availableAt());
  }

  @Test
  void exactReplayWithFreshNowPreservesDecidedAtWithoutRevisionOrWorkBump() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ToolInvocation first =
        runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));

    clock.advance(T6);
    ToolInvocation replay =
        runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));

    assertEquals(first, replay);
    assertEquals(T5, replay.approval().decidedAt());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    // 一次真实决定（fixture head advance + decision）= 2；replay 不再 bump。
    assertEquals(2L, thread.revision());
    Work toolWork =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())).orElseThrow());
    assertEquals(1L, toolWork.wakeVersion());
  }

  @Test
  void decidedApprovalReplaySurvivesLaterStopLikeStateChanges() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ToolInvocation first =
        runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));

    // 模拟 approval 之后才提交的 Stop：tool READY -> CANCELLED（decided approval 保持不变）。
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          tx.updateToolInvocations(List.of(tool.cancel(toolError(), T6)));
          return null;
        });
    ToolInvocation replay =
        runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
    assertEquals(ToolInvocationStatus.CANCELLED, replay.status());
    assertEquals(first.approval(), replay.approval());
    assertEquals(T5, replay.approval().decidedAt());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(2L, thread.revision());
  }

  @Test
  void mismatchedDecisionPayloadConflicts() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));

    HarnessRuntimeConflictException differentDecision =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.decideToolApproval(
                    new ToolApprovalCommand(
                        baseline.threadId(),
                        baseline.toolId(),
                        ToolApprovalDecision.DENIED,
                        TestIds.id(1),
                        "alice",
                        null)));
    assertEquals(Reason.APPROVAL_DECISION_MISMATCH, differentDecision.reason());

    HarnessRuntimeConflictException differentId =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.decideToolApproval(
                    new ToolApprovalCommand(
                        baseline.threadId(),
                        baseline.toolId(),
                        ToolApprovalDecision.ALLOWED,
                        TestIds.id(2),
                        "alice",
                        null)));
    assertEquals(Reason.APPROVAL_DECISION_MISMATCH, differentId.reason());

    HarnessRuntimeConflictException differentActor =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.decideToolApproval(
                    new ToolApprovalCommand(
                        baseline.threadId(),
                        baseline.toolId(),
                        ToolApprovalDecision.ALLOWED,
                        TestIds.id(1),
                        "mallory",
                        null)));
    assertEquals(Reason.APPROVAL_DECISION_MISMATCH, differentActor.reason());

    HarnessRuntimeConflictException differentReason =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.decideToolApproval(
                    new ToolApprovalCommand(
                        baseline.threadId(),
                        baseline.toolId(),
                        ToolApprovalDecision.ALLOWED,
                        TestIds.id(1),
                        "alice",
                        "changed my mind")));
    assertEquals(Reason.APPROVAL_DECISION_MISMATCH, differentReason.reason());
  }

  @Test
  void wrongThreadOrMissingRowsAreApprovalNotApplicable() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    UUID otherThread = seedThreadAt(store, baseline.assistantEntryId());

    HarnessRuntimeConflictException wrongThread =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(otherThread, baseline.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, wrongThread.reason());

    HarnessRuntimeConflictException missingThread =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(TestIds.id(999), baseline.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, missingThread.reason());

    HarnessRuntimeConflictException missingTool =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(baseline.threadId(), TestIds.id(999))));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, missingTool.reason());
  }

  @Test
  void undecidedApprovalOutsideTheCurrentContextIsNotApplicable() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    // head 移回 ROOT 后 WAITING_APPROVAL 不再属于当前 TOOL_ACTIVE 上下文。
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(thread.advanceHead(baseline.rootEntryId(), thread.yoloEnabled(), T6));
          return null;
        });
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, error.reason());
  }

  @Test
  void undecidedApprovalOnAStoppedToolIsNotApplicable() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    // Stop 风格的 WAITING_APPROVAL -> CANCELLED 保留 exact undecided approval（后续 Stop slice 的真实形状）。
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          tx.updateToolInvocations(List.of(tool.cancel(toolError(), T6)));
          return null;
        });
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, error.reason());
    // 工具保持 CANCELLED + 未决定 approval，没有任何 mutation。
    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.CANCELLED, stored.status());
    assertTrue(stored.approval().isUndecided());
  }

  @Test
  void nullOrNonRequiredApprovalIsNotApplicable() {
    HarnessRuntimeTestSupport.ToolBaseline noApproval = seedToolBaseline(store);
    HarnessRuntimeConflictException absent =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(noApproval.threadId(), noApproval.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, absent.reason());

    HarnessRuntimeTestSupport.ToolBaseline notRequired = seedToolBaseline(store);
    markApprovalNotRequired(store, notRequired);
    HarnessRuntimeConflictException nonRequired =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(notRequired.threadId(), notRequired.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, nonRequired.reason());
  }

  @Test
  void concurrentDuplicateApprovalsYieldOneDecidedState() throws Exception {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    try {
      Future<ToolInvocation> first =
          pool.submit(
              () -> {
                barrier.await();
                return runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
              });
      Future<ToolInvocation> second =
          pool.submit(
              () -> {
                barrier.await();
                return runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
              });
      ToolInvocation a = first.get();
      ToolInvocation b = second.get();
      assertEquals(a, b);
      assertEquals(ToolInvocationStatus.READY, a.status());
      assertEquals(ToolApprovalDecision.ALLOWED, a.approval().decision());
    } finally {
      pool.shutdownNow();
    }
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    // 两次调用只有一次真实决定：revision 恰好 +1（fixture head advance 1 + 一次决定 1）。
    assertEquals(2L, thread.revision());
    Work toolWork =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())).orElseThrow());
    assertEquals(1L, toolWork.wakeVersion());
  }

  @Test
  void toolApprovalCommandValidationRejectsBadFields() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolApprovalCommand(
                baseline.threadId(), TestIds.id(1), null, TestIds.id(1), "a", null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolApprovalCommand(
                baseline.threadId(), TestIds.id(1), ToolApprovalDecision.ALLOWED, null, "a", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApprovalCommand(
                baseline.threadId(),
                TestIds.id(1),
                ToolApprovalDecision.ALLOWED,
                TestIds.id(1),
                " a ",
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApprovalCommand(
                baseline.threadId(),
                TestIds.id(1),
                ToolApprovalDecision.ALLOWED,
                TestIds.id(1),
                "a",
                "r".repeat(1025)));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolApprovalCommand(
                null, TestIds.id(1), ToolApprovalDecision.ALLOWED, TestIds.id(1), "a", null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolApprovalCommand(
                TestIds.id(1), null, ToolApprovalDecision.ALLOWED, TestIds.id(1), "a", null));
  }
}
