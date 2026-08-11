package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.ENV2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedChildTurnStart;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedClaimedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedRunningModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedTerminalModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.storeAdvancingClockOnThreadLock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.withConsumedTurnStart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetYoloCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** enqueueCommands：有序命令集合入队、replay 语义、CAS 与 SET_ENVIRONMENT admission。 */
class HarnessRuntimeEnqueueTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void enqueuesNewBatchWithContinuousSequencesExactRevisionBumpAndThreadWork() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    List<ThreadCommand> inserted =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(1), "hello"), setYolo(TestIds.id(2), true))));
    assertEquals(2, inserted.size());
    assertEquals(1L, inserted.get(0).sequence());
    assertEquals(2L, inserted.get(1).sequence());
    assertEquals(ThreadCommandState.QUEUED, inserted.get(0).state());
    assertEquals(T0, inserted.get(0).createdAt());
    assertEquals(TestIds.id(1), inserted.get(0).clientCommandId());
    assertEquals(TestIds.id(2), inserted.get(1).clientCommandId());

    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(3L, thread.nextCommandSequence());
    assertEquals(1L, thread.revision());
    Work work =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElseThrow());
    assertEquals(1L, work.wakeVersion());
    assertEquals(T0, work.availableAt());
  }

  @Test
  void replayReturnsExistingQueuedRowsIgnoringStaleCursorAndLifecycle() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    List<ThreadCommand> first =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(
                    userMessageCommand(TestIds.id(1), "hello"),
                    userMessageCommand(TestIds.id(2), "world"))));

    // 重试携带过期 CAS / 完全不同的 expected 游标：replay 忽略它们。
    List<ThreadCommand> replay =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                TestIds.id(999),
                999L,
                List.of(
                    userMessageCommand(TestIds.id(1), "hello"),
                    userMessageCommand(TestIds.id(2), "world"))));
    assertEquals(first, replay);
    assertEquals(ThreadCommandState.QUEUED, replay.get(0).state());

    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(3L, thread.nextCommandSequence());
    assertEquals(1L, thread.revision());
    Work work =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElseThrow());
    // replay 不请求新 wake。
    assertEquals(1L, work.wakeVersion());
  }

  @Test
  void replayReturnsAppliedAndCancelledRowsUnchanged() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(
                userMessageCommand(TestIds.id(1), "hello"),
                userMessageCommand(TestIds.id(2), "world"))));
    UUID turnStartId = seedChildTurnStart(store, baseline.sessionId(), baseline.rootEntryId());
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          List<ThreadCommand> queued = tx.loadQueuedCommands(baseline.threadId());
          tx.updateCommands(
              queued.stream().map(c -> withConsumedTurnStart(c, turnStartId)).toList());
          return null;
        });
    List<ThreadCommand> appliedReplay =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(
                    userMessageCommand(TestIds.id(1), "hello"),
                    userMessageCommand(TestIds.id(2), "world"))));
    assertEquals(ThreadCommandState.APPLIED, appliedReplay.get(0).state());
    assertEquals(turnStartId, appliedReplay.get(0).consumedTurnStartEntryId());

    // 另一线程：CANCELLED 同样可 replay。
    HarnessRuntimeTestSupport.Baseline cancelledBaseline = seedBaseline(store);
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            cancelledBaseline.threadId(),
            cancelledBaseline.rootEntryId(),
            1,
            List.of(userMessageCommand(TestIds.id(3), "bye"))));
    store.transaction(
        tx -> {
          tx.lockThread(cancelledBaseline.threadId());
          tx.updateCommands(
              tx.loadQueuedCommands(cancelledBaseline.threadId()).stream()
                  .map(c -> c.cancel(T3))
                  .toList());
          return null;
        });
    List<ThreadCommand> cancelledReplay =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                cancelledBaseline.threadId(),
                cancelledBaseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(3), "bye"))));
    assertEquals(ThreadCommandState.CANCELLED, cancelledReplay.get(0).state());
    assertEquals(T3, cancelledReplay.get(0).cancelledAt());
  }

  @Test
  void partialReplayConflictsAndNeverFillsMissingCommands() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(userMessageCommand(TestIds.id(1), "a"))));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1,
                        List.of(
                            userMessageCommand(TestIds.id(1), "a"),
                            userMessageCommand(TestIds.id(2), "b")))));
    assertEquals(Reason.PARTIAL_COMMAND_REPLAY, error.reason());
    // 缺失命令不被补齐。
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(2)))
            .isEmpty());
  }

  @Test
  void reusedClientCommandIdWithDifferentPayloadConflicts() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(userMessageCommand(TestIds.id(1), "a"))));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1,
                        List.of(userMessageCommand(TestIds.id(1), "different")))));
    assertEquals(Reason.COMMAND_ID_REUSED, error.reason());
  }

  @Test
  void replayOrderMismatchConflictsWhenSequencesAreNotContiguousInRequestOrder() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(
                userMessageCommand(TestIds.id(1), "a"), userMessageCommand(TestIds.id(2), "b"))));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1,
                        List.of(
                            userMessageCommand(TestIds.id(2), "b"),
                            userMessageCommand(TestIds.id(1), "a")))));
    assertEquals(Reason.COMMAND_REPLAY_ORDER_MISMATCH, error.reason());
  }

  @Test
  void staleCommandCursorConflictsAndLeavesNoMutations() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(userMessageCommand(TestIds.id(1), "a"))));
    HarnessRuntimeConflictException staleSequence =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1, // 已被前一批消费
                        List.of(userMessageCommand(TestIds.id(2), "b")))));
    assertEquals(Reason.STALE_COMMAND_CURSOR, staleSequence.reason());
    HarnessRuntimeConflictException staleHead =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        baseline.threadId(),
                        TestIds.id(999),
                        2,
                        List.of(userMessageCommand(TestIds.id(3), "c")))));
    assertEquals(Reason.STALE_COMMAND_CURSOR, staleHead.reason());
    // 冲突零 mutation：无新命令、无新 wake、无 revision 变化。
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(2)))
            .isEmpty());
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(3)))
            .isEmpty());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(2L, thread.nextCommandSequence());
    assertEquals(1L, thread.revision());
    Work work =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElseThrow());
    assertEquals(1L, work.wakeVersion());
  }

  @Test
  void concurrentBatchesHaveExactlyOneCasWinner() throws Exception {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadCommandBatch batchA =
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(userMessageCommand(TestIds.id(4), "a")));
    ThreadCommandBatch batchB =
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(userMessageCommand(TestIds.id(5), "b")));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    try {
      Future<List<ThreadCommand>> futureA =
          pool.submit(
              () -> {
                barrier.await();
                return runtime.enqueueCommands(batchA);
              });
      Future<List<ThreadCommand>> futureB =
          pool.submit(
              () -> {
                barrier.await();
                return runtime.enqueueCommands(batchB);
              });
      int winners = 0;
      int staleLosers = 0;
      for (Future<List<ThreadCommand>> future : List.of(futureA, futureB)) {
        try {
          List<ThreadCommand> commands = future.get();
          assertEquals(1, commands.size());
          winners++;
        } catch (ExecutionException error) {
          assertTrue(error.getCause() instanceof HarnessRuntimeConflictException);
          assertEquals(
              Reason.STALE_COMMAND_CURSOR,
              ((HarnessRuntimeConflictException) error.getCause()).reason());
          staleLosers++;
        }
      }
      assertEquals(1, winners);
      assertEquals(1, staleLosers);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 时间戳在 Thread 锁获取之后读取：store 代理在 lockThread 时把可变时钟从 T0 推进到 T1，新命令与 Thread 必须 使用推进后的时间（锁前捕获会留下
   * createdAt/updatedAt=T0）。
   */
  @Test
  void timestampIsCapturedAfterTheThreadLock() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    TestClock clock = new TestClock(T0);
    HarnessRuntime lockedRuntime =
        new HarnessRuntime(storeAdvancingClockOnThreadLock(store, clock, T1), clock);
    List<ThreadCommand> inserted =
        lockedRuntime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(1), "hello"))));
    assertEquals(T1, inserted.get(0).createdAt());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(T1, thread.updatedAt());
  }

  @Test
  void enqueueResultsAreImmutableForFreshAndReplayBatches() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    List<ThreadCommand> fresh =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(1), "hello"))));
    assertThrows(UnsupportedOperationException.class, () -> fresh.add(null));
    List<ThreadCommand> replay =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(1), "hello"))));
    assertThrows(UnsupportedOperationException.class, () -> replay.add(null));
  }

  @Test
  void setEnvironmentFromQuiescentStateSucceeds() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    List<ThreadCommand> inserted =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(setEnvironment(TestIds.id(6), ENV2))));
    assertEquals(1, inserted.size());
    assertEquals(ThreadCommandState.QUEUED, inserted.get(0).state());
    assertEquals(new SetEnvironmentCommandPayload(ENV2), inserted.get(0).payload());
  }

  @Test
  void setEnvironmentAllowsPreExistingQueuedConfigCommand() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    // 只有 USER/CUSTOM 阻塞 SET_ENVIRONMENT；已存在的配置命令（如 SET_YOLO）不阻塞。
    seedQueuedCommand(
        store, baseline.threadId(), 1L, new SetYoloCommandPayload(true), TestIds.id(8));
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(thread.reserveCommandSequences(1, T0));
          return null;
        });
    List<ThreadCommand> inserted =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                2,
                List.of(setEnvironment(TestIds.id(6), ENV2))));
    assertEquals(1, inserted.size());
    assertEquals(2L, inserted.get(0).sequence());
  }

  @Test
  void setEnvironmentPlusMessageInOneNewBatchSucceedsFromQuiescentState() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    List<ThreadCommand> inserted =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(
                    setEnvironment(TestIds.id(6), ENV2),
                    userMessageCommand(TestIds.id(7), "run it"))));
    assertEquals(2, inserted.size());
    assertEquals(1L, inserted.get(0).sequence());
    assertEquals(2L, inserted.get(1).sequence());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(3L, thread.nextCommandSequence());
    assertEquals(1L, thread.revision());
  }

  @Test
  void setEnvironmentRejectsPreExistingQueuedUserMessage() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("hello"), TestIds.id(8));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, error.reason());
  }

  @Test
  void setEnvironmentRejectsLiveAndPendingModelContexts() {
    HarnessRuntimeTestSupport.ModelBaseline running = seedRunningModel(store);
    HarnessRuntimeConflictException active =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        running.threadId(),
                        running.turnStartEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, active.reason());

    HarnessRuntimeTestSupport.ModelBaseline pending = seedTerminalModel(store);
    HarnessRuntimeConflictException terminal =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        pending.threadId(),
                        pending.turnStartEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, terminal.reason());
  }

  @Test
  void setEnvironmentRejectsContinuationDue() {
    HarnessRuntimeTestSupport.ContinuationBaseline continuation =
        seedContinuationChain(store, true);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        continuation.threadId(),
                        continuation.turnEndEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, error.reason());
  }

  @Test
  void setEnvironmentRejectsLiveAndPendingToolContexts() {
    HarnessRuntimeTestSupport.ToolBaseline active = seedToolBaseline(store);
    setWaitingApproval(store, active);
    HarnessRuntimeConflictException liveTool =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        active.threadId(),
                        active.assistantEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, liveTool.reason());

    HarnessRuntimeTestSupport.ToolBaseline pending = seedToolBaseline(store);
    cancelTool(store, pending);
    HarnessRuntimeConflictException pendingTool =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        pending.threadId(),
                        pending.assistantEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, pendingTool.reason());
  }

  @Test
  void setEnvironmentRejectsThreadWorkRowWhetherUnleasedOrClaimed() {
    HarnessRuntimeTestSupport.Baseline unleased = seedBaseline(store);
    seedThreadWork(store, unleased.threadId());
    HarnessRuntimeConflictException unleasedError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        unleased.threadId(),
                        unleased.rootEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, unleasedError.reason());

    HarnessRuntimeTestSupport.Baseline claimed = seedBaseline(store);
    seedClaimedThreadWork(store, claimed.threadId());
    HarnessRuntimeConflictException claimedError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.enqueueCommands(
                    new ThreadCommandBatch(
                        claimed.threadId(),
                        claimed.rootEntryId(),
                        1,
                        List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertEquals(Reason.THREAD_NOT_QUIESCENT, claimedError.reason());
  }

  @Test
  void setEnvironmentAdmissionConflictsLeaveNoMutations() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedThreadWork(store, baseline.threadId());
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            runtime.enqueueCommands(
                new ThreadCommandBatch(
                    baseline.threadId(),
                    baseline.rootEntryId(),
                    1,
                    List.of(setEnvironment(TestIds.id(6), ENV2)))));
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(6)))
            .isEmpty());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(1L, thread.nextCommandSequence());
    assertEquals(0L, thread.revision());
  }

  @Test
  void exactReplayBypassesSetEnvironmentAdmission() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(setEnvironment(TestIds.id(6), ENV2))));
    // 之后线程出现 queued USER 与 THREAD Work：exact replay 仍然直接返回原行。
    seedQueuedCommand(store, baseline.threadId(), 99L, userMessagePayload("late"), TestIds.id(9));
    seedThreadWork(store, baseline.threadId());
    List<ThreadCommand> replay =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(setEnvironment(TestIds.id(6), ENV2))));
    assertEquals(1, replay.size());
    assertEquals(ThreadCommandState.QUEUED, replay.get(0).state());
    assertEquals(new SetEnvironmentCommandPayload(ENV2), replay.get(0).payload());
  }

  @Test
  void nonSetEnvironmentBatchIsAllowedWhileModelIsRunning() {
    HarnessRuntimeTestSupport.ModelBaseline running = seedRunningModel(store);
    List<ThreadCommand> inserted =
        runtime.enqueueCommands(
            new ThreadCommandBatch(
                running.threadId(),
                running.turnStartEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(1), "hello"))));
    assertEquals(1, inserted.size());
    ThreadState thread = store.transaction(tx -> tx.findThread(running.threadId()).orElseThrow());
    assertEquals(2L, thread.nextCommandSequence());
    assertEquals(1L, thread.revision());
  }

  @Test
  void enqueueOnMissingThreadFailsNotFound() {
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () ->
            runtime.enqueueCommands(
                new ThreadCommandBatch(
                    TestIds.id(999),
                    TestIds.id(1),
                    1L,
                    List.of(userMessageCommand(TestIds.id(1), "hello")))));
  }

  @Test
  void commandBatchValidationStillRejectsEmptyOrDuplicateClientIds() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommandBatch(baseline.threadId(), baseline.rootEntryId(), 1, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommandBatch(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(
                    userMessageCommand(TestIds.id(10), "a"),
                    userMessageCommand(TestIds.id(10), "b"))));
  }

  private static NewThreadCommand setEnvironment(
      UUID clientCommandId, EnvironmentName environmentName) {
    return new NewThreadCommand(new SetEnvironmentCommandPayload(environmentName), clientCommandId);
  }

  private static NewThreadCommand setYolo(UUID clientCommandId, boolean yolo) {
    return new NewThreadCommand(new SetYoloCommandPayload(yolo), clientCommandId);
  }
}
