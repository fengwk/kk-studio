package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedChildTurnStart;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedForeignRoot;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedRunningModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedTerminalModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedYoloThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.storeAdvancingClockOnThreadLock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** moveHead：no-op replay、revision CAS、跨 session / 静默期 / continuation-obligation 守卫。 */
class HarnessRuntimeMoveHeadTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T3, ZoneOffset.UTC));
  }

  @Test
  void sameHeadIsPutStyleNoOpReplayBeforeAnyCheckWithoutRevisionBump() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    // 即使 expectedRevision 完全不匹配，head 已等于 target 时按 no-op 返回。
    ThreadState result =
        runtime.moveHead(new MoveHeadCommand(baseline.threadId(), baseline.rootEntryId(), 42));
    assertEquals(baseline.rootEntryId(), result.headEntryId());
    assertEquals(0L, result.revision());
    // 即使存在 queued command 也先走 no-op 分支。
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("hi"), TestIds.id(1));
    ThreadState resultWithQueued =
        runtime.moveHead(new MoveHeadCommand(baseline.threadId(), baseline.rootEntryId(), 42));
    assertEquals(0L, resultWithQueued.revision());
  }

  @Test
  void staleRevisionConflicts() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID target = seedChildTurnStart(store, baseline.sessionId(), baseline.rootEntryId());
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.moveHead(new MoveHeadCommand(baseline.threadId(), target, 7)));
    assertEquals(Reason.STALE_REVISION, error.reason());
  }

  /**
   * 时间戳在 Thread 锁获取之后读取：store 代理在 lockThread 时把可变时钟从 T0 推进到 T1，移动后的 Thread 必须 使用推进后的时间（锁前捕获会留下
   * updatedAt=T0）。
   */
  @Test
  void movedThreadUsesTheTimestampCapturedAfterTheThreadLock() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID target = seedChildTurnStart(store, baseline.sessionId(), baseline.rootEntryId());
    TestClock clock = new TestClock(T0);
    HarnessRuntime lockedRuntime =
        new HarnessRuntime(storeAdvancingClockOnThreadLock(store, clock, T1), clock);
    ThreadState moved = lockedRuntime.moveHead(new MoveHeadCommand(baseline.threadId(), target, 0));
    assertEquals(T1, moved.updatedAt());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(T1, thread.updatedAt());
  }

  @Test
  void missingThreadOrTargetEntryIsNotFound() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> runtime.moveHead(new MoveHeadCommand(TestIds.id(999), baseline.rootEntryId(), 0)));
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> runtime.moveHead(new MoveHeadCommand(baseline.threadId(), TestIds.id(999), 0)));
  }

  @Test
  void crossSessionTargetConflicts() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID foreignRoot = seedForeignRoot(store);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.moveHead(new MoveHeadCommand(baseline.threadId(), foreignRoot, 0)));
    assertEquals(Reason.MOVE_TARGET_CROSS_SESSION, error.reason());
  }

  @Test
  void queuedCommandsBlockMoveHead() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID target = seedChildTurnStart(store, baseline.sessionId(), baseline.rootEntryId());
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("hi"), TestIds.id(1));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.moveHead(new MoveHeadCommand(baseline.threadId(), target, 0)));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, error.reason());
  }

  @Test
  void liveModelContextBlocksMoveHead() {
    HarnessRuntimeTestSupport.ModelBaseline running = seedRunningModel(store);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.moveHead(
                    new MoveHeadCommand(running.threadId(), running.rootEntryId(), 0)));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, error.reason());
  }

  @Test
  void pendingModelContextBlocksMoveHeadWithTerminalApplyPending() {
    HarnessRuntimeTestSupport.ModelBaseline pending = seedTerminalModel(store);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.moveHead(
                    new MoveHeadCommand(pending.threadId(), pending.rootEntryId(), 0)));
    assertEquals(Reason.TERMINAL_APPLY_PENDING, error.reason());
  }

  @Test
  void liveToolContextBlocksMoveHead() {
    HarnessRuntimeTestSupport.ToolBaseline active = seedToolBaseline(store);
    setWaitingApproval(store, active);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.moveHead(new MoveHeadCommand(active.threadId(), active.rootEntryId(), 1)));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, error.reason());
  }

  @Test
  void pendingToolContextBlocksMoveHeadWithTerminalApplyPending() {
    HarnessRuntimeTestSupport.ToolBaseline pending = seedToolBaseline(store);
    cancelTool(store, pending);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.moveHead(
                    new MoveHeadCommand(pending.threadId(), pending.rootEntryId(), 1)));
    assertEquals(Reason.TERMINAL_APPLY_PENDING, error.reason());
  }

  @Test
  void movingAwayFromContinuationDueIsAllowedAndPreservesYolo() {
    HarnessRuntimeTestSupport.ContinuationBaseline continuation =
        seedContinuationChain(store, true);
    UUID threadId = seedYoloThreadAt(store, continuation.turnEndEntryId());
    ThreadState moved =
        runtime.moveHead(new MoveHeadCommand(threadId, continuation.rootEntryId(), 0));
    assertEquals(continuation.rootEntryId(), moved.headEntryId());
    assertTrue(moved.yoloEnabled());
    assertEquals(1L, moved.revision());
    assertEquals(1L, moved.nextCommandSequence());
  }

  @Test
  void targetWithContinuationObligationIsRejected() {
    HarnessRuntimeTestSupport.ContinuationBaseline chain = seedContinuationChain(store, false);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.moveHead(new MoveHeadCommand(chain.threadId(), chain.turnEndEntryId(), 0)));
    assertEquals(Reason.MOVE_TARGET_HAS_CONTINUATION_OBLIGATION, error.reason());
  }

  @Test
  void historicalOpenTurnPrefixIsAnAllowedTarget() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID openTurnStart = seedChildTurnStart(store, baseline.sessionId(), baseline.rootEntryId());
    ThreadState moved =
        runtime.moveHead(new MoveHeadCommand(baseline.threadId(), openTurnStart, 0));
    assertEquals(openTurnStart, moved.headEntryId());
    assertEquals(1L, moved.revision());
  }

  @Test
  void anotherThreadsAssistantEntryIsAnAllowedTarget() {
    HarnessRuntimeTestSupport.ToolBaseline tool = seedToolBaseline(store);
    setWaitingApproval(store, tool);
    // 同一 Session 的第二 Thread 指向 ROOT；目标为第一 Thread 的 Assistant head（历史 open prefix）。
    UUID secondThread =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, tool.rootEntryId()));
              return id;
            });
    ThreadState moved =
        runtime.moveHead(new MoveHeadCommand(secondThread, tool.assistantEntryId(), 0));
    assertEquals(tool.assistantEntryId(), moved.headEntryId());
    assertEquals(1L, moved.revision());
  }

  @Test
  void moveHeadNeverRequestsWorkAndForceDeletesThreadWorkAsFence() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID target = seedChildTurnStart(store, baseline.sessionId(), baseline.rootEntryId());
    runtime.moveHead(new MoveHeadCommand(baseline.threadId(), target, 0));
    // 无 Work 被请求。
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())).isEmpty()));

    // 预置 THREAD Work（模拟 speculative Resolver mailbox）：move 后最后强制删除。
    HarnessRuntimeTestSupport.Baseline fenced = seedBaseline(store);
    UUID fencedTarget = seedChildTurnStart(store, fenced.sessionId(), fenced.rootEntryId());
    seedThreadWork(store, fenced.threadId());
    runtime.moveHead(new MoveHeadCommand(fenced.threadId(), fencedTarget, 0));
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, fenced.threadId())).isEmpty()));
  }

  @Test
  void moveHeadKeepsLatestNextCommandSequenceOnAnyMove() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID target = seedChildTurnStart(store, baseline.sessionId(), baseline.rootEntryId());
    // 先入队 4 条命令推进 nextCommandSequence（revision 1），再取消它们解除 queued 阻塞。
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(
                userMessageCommand(TestIds.id(1), "a"),
                userMessageCommand(TestIds.id(2), "b"),
                userMessageCommand(TestIds.id(3), "c"),
                userMessageCommand(TestIds.id(4), "d"))));
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.updateCommands(
              tx.loadQueuedCommands(baseline.threadId()).stream().map(c -> c.cancel(T3)).toList());
          return null;
        });
    ThreadState moved = runtime.moveHead(new MoveHeadCommand(baseline.threadId(), target, 1));
    assertEquals(target, moved.headEntryId());
    assertEquals(5L, moved.nextCommandSequence());
    assertEquals(2L, moved.revision());
  }

  @Test
  void moveHeadValidationRejectsBadRequestFields() {
    assertThrows(NullPointerException.class, () -> new MoveHeadCommand(null, TestIds.id(1), 0));
    assertThrows(NullPointerException.class, () -> new MoveHeadCommand(TestIds.id(1), null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MoveHeadCommand(TestIds.id(1), TestIds.id(1), -1));
  }
}
