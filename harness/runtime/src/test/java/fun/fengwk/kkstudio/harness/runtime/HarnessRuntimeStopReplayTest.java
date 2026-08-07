package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * Stop 幂等性：thread 作用域的 durable key 在 revision 检查之前就严格决定 replay， 绝不在 Thread 之间产生别名冲突，且在非 Stop 关闭或重复
 * key 时必须失败。
 */
class HarnessRuntimeStopReplayTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void firstStopThenStaleReplayReturnsTheOriginalStoppedTurnEnd() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.STOPPED, first.status());
    long turnEndId = first.stoppedTurnEndEntryId();

    // 重放先于 revision 检查：即使 expectedRevision 已过期也返回 REPLAYED，且不取消任何 Command。
    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.REPLAYED, replay.status());
    assertEquals(turnEndId, replay.stoppedTurnEndEntryId());
    assertEquals(0, replay.cancelledCommandCount());
    assertEquals(first.thread().revision(), replay.thread().revision());
    assertEquals(first.thread().headEntryId(), replay.thread().headEntryId());
  }

  @Test
  void ancestorStoppedEndReplaysWithoutMutatingTheNewActiveTurn() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    long stoppedEndId = first.stoppedTurnEndEntryId();
    // 新 Turn 的 TURN_START 必须晚于 Stop 追加的 TURN_END（createdAt 链约束）。
    long[] nextTurn =
        store.transaction(
            tx -> {
              long turnStartId = tx.nextId();
              tx.insertEntry(turnStartEntry(turnStartId, baseline.sessionId(), stoppedEndId, T5));
              long userEntryId = tx.nextId();
              tx.insertEntry(userMessageEntry(userEntryId, baseline.sessionId(), turnStartId, T5));
              return new long[] {turnStartId, userEntryId};
            });
    long turnStart2 = nextTurn[0];
    long userEntry2 = nextTurn[1];
    runtime.moveHead(new MoveHeadCommand(baseline.threadId(), userEntry2, 1));
    // 新 Turn 已有自己的 RUNNING Model（MODEL_ACTIVE），其 Work 全部存在。
    long model2 =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              long modelId = tx.nextId();
              ModelInvocation model =
                  modelInvocation(modelId, baseline.threadId(), turnStart2, userEntry2, T5);
              tx.insertModelInvocation(model);
              tx.updateModelInvocation(model.beginDispatch(T5));
              tx.updateModelInvocation(model.beginDispatch(T5).markRunning(T5));
              return modelId;
            });
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, model2);

    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 2));
    assertEquals(StopResult.Status.REPLAYED, replay.status());
    assertEquals(stoppedEndId, replay.stoppedTurnEndEntryId());
    // 当前 Turn 零 mutation：head/revision 不变，Model 仍 RUNNING，Work 全部保留。
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(userEntry2, thread.headEntryId());
    assertEquals(2L, thread.revision());
    ModelInvocation model = store.transaction(tx -> tx.findModelInvocation(model2).orElseThrow());
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertTrue(
        Boolean.TRUE.equals(
            store.transaction(
                tx ->
                    tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                        .isPresent())));
    assertTrue(
        Boolean.TRUE.equals(
            store.transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, model2)).isPresent())));
  }

  /**
   * 同一 external id 在两个 Thread 上各自形成 thread-scoped durable key：T2 与 T1 共享同一个 CONTINUATION TURN_END
   * 历史，但 T2 的 Stop 不是 T1 的重放，且写入自己的 key。
   */
  @Test
  void sameExternalIdOnSharedHistoryIsNotReplayAndKeysDiffer() {
    HarnessRuntimeTestSupport.ContinuationBaseline chain = seedContinuationChain(store, true);
    long otherThread = seedThreadAt(store, chain.turnEndEntryId());
    StopResult first = runtime.stop(new StopCommand(chain.threadId(), "stop-1", 1));
    assertEquals(StopResult.Status.STOPPED, first.status());
    String key1 = "STOP/" + chain.threadId() + "/stop-1";
    assertEquals(key1, stoppedEnd(chain.threadId()).closeRequestId());

    StopResult second = runtime.stop(new StopCommand(otherThread, "stop-1", 0));
    assertEquals(StopResult.Status.STOPPED, second.status());
    assertNotEquals(first.stoppedTurnEndEntryId(), second.stoppedTurnEndEntryId());
    String key2 = "STOP/" + otherThread + "/stop-1";
    assertNotEquals(key1, key2);
    assertEquals(key2, stoppedEnd(otherThread).closeRequestId());
    // T1 自己的 key 仍是精确重放（revision 已过期也返回 REPLAYED）。
    assertEquals(
        StopResult.Status.REPLAYED,
        runtime.stop(new StopCommand(chain.threadId(), "stop-1", 0)).status());
  }

  /**
   * 最小的 ownership 反例：T2 迁移到 T1 已停止的 continuation 分支。T2 的路径上虽然存在 原始 external id，但 durable key 限定在 T1
   * 作用域内，因此 T2 不应 replay 该 id。
   */
  @Test
  void anotherThreadOnTheStoppedBranchDoesNotReplayTheRawExternalId() {
    HarnessRuntimeTestSupport.ContinuationBaseline chain = seedContinuationChain(store, true);
    long otherThread = seedThreadAt(store, chain.turnEndEntryId());
    StopResult first = runtime.stop(new StopCommand(chain.threadId(), "shared-id", 1));

    ThreadState relocated =
        runtime.moveHead(new MoveHeadCommand(otherThread, first.stoppedTurnEndEntryId(), 0));
    assertEquals(1L, relocated.revision());
    StopResult otherResult = runtime.stop(new StopCommand(otherThread, "shared-id", 1));

    assertEquals(StopResult.Status.IDLE, otherResult.status());
    assertNull(otherResult.stoppedTurnEndEntryId());
    assertEquals(first.stoppedTurnEndEntryId(), otherResult.thread().headEntryId());
    assertEquals(1L, otherResult.thread().revision());
    assertEquals(
        "STOP/" + chain.threadId() + "/shared-id", stoppedEnd(otherThread).closeRequestId());
    assertNotEquals(
        StopControl.durableKey(otherThread, "shared-id"), stoppedEnd(otherThread).closeRequestId());
  }

  @Test
  void exactKeyOnNonStoppedCloseConflicts() {
    long threadId = 11L;
    long sessionId = 21L;
    long rootId = 31L;
    long turnStartId = 41L;
    long userId = 51L;
    long assistantId = 61L;
    long turnEndId = 71L;
    inTransaction(
        store,
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartId, sessionId, rootId, T1));
          tx.insertEntry(userMessageEntry(userId, sessionId, turnStartId, T1));
          tx.insertEntry(assistantEntry(assistantId, sessionId, userId, T1));
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  sessionId,
                  assistantId,
                  new TurnEndPayload(
                      turnStartId,
                      TurnEndOutcome.CANCELLED,
                      false,
                      TurnEndReason.HISTORY_CUT,
                      "STOP/" + threadId + "/stop-1"),
                  T1));
          tx.insertThread(HarnessRuntimeTestSupport.thread(threadId, turnEndId));
        });
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(threadId, "stop-1", 0)));
    assertEquals(Reason.STOP_REQUEST_ID_REUSED, error.reason());
  }

  @Test
  void duplicateExactKeyOnTheCurrentPathIsAnInvariantViolation() {
    long threadId = 12L;
    long sessionId = 22L;
    long rootId = 32L;
    long turnStart1 = 42L;
    long turnEnd1 = 72L;
    long turnStart2 = 43L;
    long turnEnd2 = 73L;
    String key = "STOP/" + threadId + "/stop-1";
    inTransaction(
        store,
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertEntry(turnStartEntry(turnStart1, sessionId, rootId, T1));
          tx.insertEntry(userMessageEntry(51L, sessionId, turnStart1, T1));
          tx.insertEntry(assistantEntry(61L, sessionId, 51L, T1));
          tx.insertEntry(
              new Entry(
                  turnEnd1,
                  sessionId,
                  61L,
                  new TurnEndPayload(
                      turnStart1, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, key),
                  T1));
          tx.insertEntry(turnStartEntry(turnStart2, sessionId, turnEnd1, T1));
          tx.insertEntry(userMessageEntry(52L, sessionId, turnStart2, T1));
          tx.insertEntry(assistantEntry(62L, sessionId, 52L, T1));
          tx.insertEntry(
              new Entry(
                  turnEnd2,
                  sessionId,
                  62L,
                  new TurnEndPayload(
                      turnStart2, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, key),
                  T1));
          tx.insertThread(HarnessRuntimeTestSupport.thread(threadId, turnEnd2));
        });
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> runtime.stop(new StopCommand(threadId, "stop-1", 0)));
    assertTrue(error.getMessage().contains("more than once"));
  }

  @Test
  void retryAfterRelocationToASiblingWithoutTheStopKeyIsStale() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    long siblingHead =
        store.transaction(
            tx -> {
              long turnStartId = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(turnStartId, baseline.sessionId(), baseline.rootEntryId(), T5));
              long userEntryId = tx.nextId();
              tx.insertEntry(userMessageEntry(userEntryId, baseline.sessionId(), turnStartId, T5));
              return userEntryId;
            });
    runtime.moveHead(new MoveHeadCommand(baseline.threadId(), siblingHead, 1));

    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0)));

    assertEquals(Reason.STALE_REVISION, error.reason());
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(siblingHead, thread.headEntryId());
    assertEquals(2L, thread.revision());
  }

  @Test
  void staleRevisionInToolContextFailsWithoutMutatingSiblings() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    // Approval 已把 revision 推进到 2；带旧 revision 的 Stop 重试必须整体拒绝。
    runtime.decideToolApproval(
        new ToolApprovalCommand(
            baseline.threadId(),
            baseline.toolId(),
            ToolApprovalDecision.ALLOWED,
            "dec-1",
            "tester",
            "approve"));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 1)));
    assertEquals(Reason.STALE_REVISION, error.reason());
    ToolInvocation tool =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertNull(tool.resultEntryId());
    assertEquals(
        2L, store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow()).revision());
    assertTrue(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())))
            .isPresent());
  }

  @Test
  void idleRepeatIsIdleNotReplayed() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.IDLE, first.status());
    // idle Stop 不写 Entry，因此同 key 再次请求仍是 IDLE（没有可重放的 TURN_END）。
    StopResult second = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.IDLE, second.status());
    assertEquals(0L, second.thread().revision());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(baseline.rootEntryId()));
    assertEquals(1, path.entries().size());
  }

  @Test
  void idleCommandRetryWithOldRevisionIsStale() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, 1L, userMessagePayload("hi"), "cid-1");
    seedThreadWork(store, baseline.threadId());
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.IDLE, first.status());
    assertEquals(1, first.cancelledCommandCount());
    assertEquals(1L, first.thread().revision());
    // idle Stop 无 Entry 可重放：同一网络重试携带旧 revision 只能按 STALE_REVISION 拒绝。
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0)));
    assertEquals(Reason.STALE_REVISION, error.reason());
  }

  @Test
  void missingThreadIsNotFound() {
    HarnessRuntimeNotFoundException error =
        assertThrows(
            HarnessRuntimeNotFoundException.class,
            () -> runtime.stop(new StopCommand(999L, "stop-1", 0)));
    assertNotNull(error.getMessage());
  }

  /** 返回 thread 当前路径上最新的 STOPPED TURN_END（即 Stop 追加的那一条）。 */
  private TurnEndPayload stoppedEnd(long threadId) {
    ThreadState thread = store.transaction(tx -> tx.lockThread(threadId).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    for (int i = path.entries().size() - 1; i >= 0; i--) {
      Entry entry = path.entries().get(i);
      if (entry.payload() instanceof TurnEndPayload end
          && end.outcome() == TurnEndOutcome.STOPPED) {
        return end;
      }
    }
    throw new IllegalStateException("no stopped TURN_END on thread " + threadId);
  }
}
