package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertReplayed;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
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
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.targetReceipt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Stop 幂等性：durable key 是「由被关闭 TURN_START 的 ownerThreadId 界定的 closeRequestId」，在 Thread 锁内做 Session 级
 * 查找并先于 version 检查严格决定 replay；另一 Thread 的相同 raw id 被忽略而非冲突；同 owner 的非 Stop 关闭或重复 key 必须失败； 未创建 Turn
 * 的 queued 取消以 (threadId, stopRequestId) 作幂等键。
 */
class HarnessRuntimeStopReplayTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void firstStopThenReplayReturnsTheOriginalStoppedTurnEnd() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(first);
    UUID turnEndId = targetReceipt(first).stoppedTurnEndEntryId();

    // 重放先于 version 检查：即使 expectedVersion 已过期也返回 replayed，且不取消任何 Command。
    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertReplayed(replay);
    assertEquals(turnEndId, targetReceipt(replay).stoppedTurnEndEntryId());
    assertEquals(0, targetReceipt(replay).cancelledCommandCount());
    assertEquals(first.thread().version(), replay.thread().version());
    assertEquals(first.thread().headEntryId(), replay.thread().headEntryId());
  }

  /** 祖先已被 Stop 的 TURN_END 在 head 推进到新活跃 Turn 后仍精确 replay，且零 mutation。 */
  @Test
  void replayOnAncestorStoppedEndLeavesTheNewActiveTurnUntouched() {
    UUID sessionId = TestIds.id(21);
    UUID rootId = TestIds.id(31);
    UUID turnStart1 = TestIds.id(41);
    UUID user1 = TestIds.id(51);
    UUID assistant1 = TestIds.id(61);
    UUID turnEnd1 = TestIds.id(71);
    UUID turnStart2 = TestIds.id(42);
    UUID user2 = TestIds.id(52);
    UUID threadId = TestIds.id(11);
    UUID modelId = TestIds.id(81);
    UUID key = TestIds.id(1);
    inTransaction(
        store,
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertEntry(turnStartEntry(turnStart1, sessionId, rootId, T1, threadId));
          tx.insertEntry(userMessageEntry(user1, sessionId, turnStart1, T1));
          tx.insertEntry(assistantEntry(assistant1, sessionId, user1, T1));
          tx.insertEntry(
              new Entry(
                  turnEnd1,
                  sessionId,
                  assistant1,
                  new TurnEndPayload(
                      turnStart1, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, key),
                  T1));
          tx.insertEntry(turnStartEntry(turnStart2, sessionId, turnEnd1, T1, threadId));
          tx.insertEntry(userMessageEntry(user2, sessionId, turnStart2, T1));
          // 新活跃 open Turn：head 停在 user2，Model 以 head（turned 2）为基础在跑。
          tx.insertThread(thread(threadId, sessionId, user2));
          ModelInvocation model = modelInvocation(modelId, threadId, turnStart2, user2, T1);
          tx.insertModelInvocation(model);
          tx.updateModelInvocation(model.beginDispatch(T2));
          tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
        });
    // 该 STOP 边界已持久保存回执：replay 完全由 (threadId, stopRequestId) 回执驱动，不读当前 head。
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          tx.insertStopReceipts(
              key, List.of(new StoppedThreadReceipt(threadId, key, turnEnd1, 0, List.of())));
          return null;
        });
    seedThreadWork(store, threadId);
    seedModelWork(store, modelId);

    // replay 先于 version CAS：即使 expectedVersion 已过期，也返回原 STOPPED TURN_END 且零 mutation。
    StopResult replay = runtime.stop(new StopCommand(threadId, key, 9));
    assertReplayed(replay);
    assertEquals(turnEnd1, targetReceipt(replay).stoppedTurnEndEntryId());
    ThreadState thread = store.transaction(tx -> tx.lockThread(threadId).orElseThrow());
    assertEquals(user2, thread.headEntryId());
    assertEquals(0L, thread.version());
    ModelInvocation model = store.transaction(tx -> tx.findModelInvocation(modelId).orElseThrow());
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertTrue(
        Boolean.TRUE.equals(
            store.transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)).isPresent())));
    assertTrue(
        Boolean.TRUE.equals(
            store.transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, modelId)).isPresent())));
  }

  /** 同一 raw id 归 owner Thread；共享同一 CONTINUATION 历史的 foreign Thread 的 Stop 被忽略而非冲突/stale。 */
  @Test
  void foreignOwnerRawIdOnSharedContinuationIsIgnoredAndOwnerStillReplays() {
    HarnessRuntimeTestSupport.ContinuationBaseline chain = seedContinuationChain(store, true);
    UUID sibling = seedThreadAt(store, chain.turnEndEntryId());
    StopResult owner = runtime.stop(new StopCommand(chain.threadId(), TestIds.id(1), 1));
    assertStopped(owner);

    // sibling 的上下文是 foreign CONTINUATION → IDLE_OR_HISTORICAL：同一 raw id 不 replay、不冲突；这次 Stop 也不是
    // 空动作，它在 sibling 上写自己的 STOP barrier Turn（与 owner 的 TURN_END 互不相同）。
    StopResult siblingResult = runtime.stop(new StopCommand(sibling, TestIds.id(1), 0));
    assertFalse(siblingResult.replayed());
    assertEquals(0, targetReceipt(siblingResult).cancelledCommandCount());
    assertEquals(1L, siblingResult.thread().version());
    assertEquals(
        targetReceipt(siblingResult).stoppedTurnEndEntryId(), siblingResult.thread().headEntryId());
    assertNotEquals(
        targetReceipt(owner).stoppedTurnEndEntryId(),
        targetReceipt(siblingResult).stoppedTurnEndEntryId());

    // owner 自己的 key 仍是精确重放（version 已过期也返回 REPLAYED）。
    StopResult ownerReplay = runtime.stop(new StopCommand(chain.threadId(), TestIds.id(1), 0));
    assertReplayed(ownerReplay);
    assertEquals(
        targetReceipt(owner).stoppedTurnEndEntryId(),
        targetReceipt(ownerReplay).stoppedTurnEndEntryId());
  }

  /**
   * live Stop 首次同时取消 queued commands 后，transport 丢失时同一 stopRequestId 的重试必须以同一 cancelledCommandCount
   * 与同一 sequence-ordered cancelledUserMessages 返回（幂等重试不丢失取消事实）。
   */
  @Test
  void liveStopReplayReturnsSameCancellationFactsWhenTransportLost() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    seedQueuedCommand(
        store, baseline.threadId(), 1L, userMessagePayload("please stop"), TestIds.id(1));
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());

    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(9), 0));
    assertStopped(first);
    assertNotNull(targetReceipt(first).stoppedTurnEndEntryId());
    assertEquals(1, targetReceipt(first).cancelledCommandCount());
    assertEquals(1, targetReceipt(first).cancelledInputs().size());

    // transport 丢失：同一 stopRequestId 重试（replay 先于 version CAS，expectedVersion 过期也无碍）。
    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(9), 0));
    assertReplayed(replay);
    assertEquals(
        targetReceipt(first).stoppedTurnEndEntryId(),
        targetReceipt(replay).stoppedTurnEndEntryId());
    assertEquals(
        targetReceipt(first).cancelledCommandCount(),
        targetReceipt(replay).cancelledCommandCount());
    assertEquals(targetReceipt(first).cancelledInputs(), targetReceipt(replay).cancelledInputs());
    // replay 不写任何新 marker：version 与 head 保持首次 Stop 后的终态。
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(replay.thread().version(), thread.version());
    assertEquals(targetReceipt(replay).stoppedTurnEndEntryId(), thread.headEntryId());
    // 取消行仍以该 stopRequestId 持久化（queued-only 维度与 live receipt 一致）。
    assertEquals(
        1,
        store
            .transaction(
                tx -> tx.loadCancelledCommandsByRequest(baseline.threadId(), TestIds.id(9)))
            .size());
  }

  /**
   * 历史中的 closeRequestId 不参与 Stop replay：Stop 只按 (threadId, stopRequestId) 的持久回执判定。非 STOPPED 的
   * TURN_END 即使携带相同 raw id 也不构成冲突，新 Stop 正常写入边界并可在其后按 id 重放。
   */
  @Test
  void historyCloseRequestIdDoesNotBlockNewStop() {
    UUID threadId = TestIds.id(11);
    UUID sessionId = TestIds.id(21);
    UUID rootId = TestIds.id(31);
    UUID turnStartId = TestIds.id(41);
    UUID userId = TestIds.id(51);
    UUID assistantId = TestIds.id(61);
    UUID turnEndId = TestIds.id(71);
    inTransaction(
        store,
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartId, sessionId, rootId, T1, threadId));
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
                      TestIds.id(1)),
                  T1));
          tx.insertThread(thread(threadId, sessionId, turnEndId));
        });

    StopResult first = runtime.stop(new StopCommand(threadId, TestIds.id(1), 0));
    assertStopped(first);
    assertEquals(TestIds.id(1), targetReceipt(first).stopRequestId());
    assertNotNull(targetReceipt(first).stoppedTurnEndEntryId());

    StopResult replay = runtime.stop(new StopCommand(threadId, TestIds.id(1), 0));
    assertReplayed(replay);
    assertEquals(
        targetReceipt(first).stoppedTurnEndEntryId(),
        targetReceipt(replay).stoppedTurnEndEntryId());
  }

  /** 回执身份 (threadId, stopRequestId) 不可重用：同键第二次写入被确定性拒绝。 */
  @Test
  void duplicateStopReceiptIdentityIsAnInvariantViolation() {
    UUID threadId = TestIds.id(12);
    UUID sessionId = TestIds.id(22);
    UUID rootId = TestIds.id(32);
    UUID stopRequestId = TestIds.id(1);
    inTransaction(
        store,
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertThread(thread(threadId, sessionId, rootId));
        });
    StoppedThreadReceipt receipt =
        new StoppedThreadReceipt(threadId, stopRequestId, null, 0, List.of());
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          tx.insertStopReceipts(stopRequestId, List.of(receipt));
          return null;
        });
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.lockThread(threadId);
                      tx.insertStopReceipts(stopRequestId, List.of(receipt));
                      return null;
                    }));
    assertTrue(error.getMessage().contains("already exists"));
  }

  @Test
  void staleVersionInToolContextFailsWithoutMutatingSiblings() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    // Approval 已把 version 推进到 2；带旧 version 的 Stop 重试必须整体拒绝。
    runtime.decideToolApproval(
        new ToolApprovalCommand(
            baseline.threadId(),
            baseline.toolId(),
            ToolApprovalDecision.ALLOWED,
            TestIds.id(3),
            "tester",
            "approve"));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1)));
    assertEquals(Reason.STALE_VERSION, error.reason());
    ToolInvocation tool =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(
        2L, store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow()).version());
    assertTrue(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())))
            .isPresent());
  }

  @Test
  void repeatedIdleStopWithTheSameKeyReplaysItsOwnStopBarrierTurn() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(first);
    // ROOT + STOP TURN_START + ASSISTANT_ERROR barrier + STOPPED TURN_END。
    EntryPath firstPath = store.transaction(tx -> tx.loadEntryPath(first.thread().headEntryId()));
    assertEquals(4, firstPath.entries().size());

    // idle Stop 现在留 durable receipt（STOP barrier Turn），因此同 key 再次请求是精确重放：同一条 TURN_END、不写新 Entry、
    // 不动 version。
    StopResult second = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertReplayed(second);
    assertEquals(
        targetReceipt(first).stoppedTurnEndEntryId(),
        targetReceipt(second).stoppedTurnEndEntryId());
    assertEquals(first.thread().version(), second.thread().version());
    assertEquals(0, targetReceipt(second).cancelledCommandCount());
    assertEquals(
        4,
        store.transaction(tx -> tx.loadEntryPath(second.thread().headEntryId())).entries().size());
  }

  /** live receipt 与取消事实一致：idle Stop 同时取消 queued Command 时，重试必须返回同一条 TURN_END 与同一取消事实。 */
  @Test
  void idleCommandRetryReplaysTheStopBarrierTurnAndCancellationFacts() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("hi"), TestIds.id(1));
    seedThreadWork(store, baseline.threadId());
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(first);
    assertEquals(1, targetReceipt(first).cancelledCommandCount());
    assertEquals(
        List.of(new CancelledThreadInput(1L, TestIds.id(1), userMessagePayload("hi"))),
        targetReceipt(first).cancelledInputs());
    assertEquals(1L, first.thread().version());

    // 同一网络重试命中 live receipt（STOP barrier Turn）：返回 replayed、同一条 TURN_END 与同一取消事实，不再第二次取消。
    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertReplayed(replay);
    assertEquals(
        targetReceipt(first).stoppedTurnEndEntryId(),
        targetReceipt(replay).stoppedTurnEndEntryId());
    assertEquals(1, targetReceipt(replay).cancelledCommandCount());
    assertEquals(targetReceipt(first).cancelledInputs(), targetReceipt(replay).cancelledInputs());
    assertEquals(1L, replay.thread().version());
  }

  /**
   * 旧数据兼容：只有 queued 取消行、没有 STOP barrier Turn 的 receipt（本契约引入之前写入的历史）仍以 {@code (threadId,
   * stopRequestId)} 作幂等键重放，且不补写 marker。
   */
  @Test
  void foreignOwnedIdleStopReplaysItsQueuedOnlyReceipt() {
    // open Turn 属于其它 Thread：停止只取消排队 Command、不写停止边界，因此首次与重放都以
    // (threadId, stopRequestId) 的 queued-only receipt 作为幂等键。
    HarnessRuntimeTestSupport.TurnBaseline owner = HarnessRuntimeTestSupport.seedOpenTurn(store);
    UUID sibling = HarnessRuntimeTestSupport.seedThreadAt(store, owner.turnStartEntryId());
    seedQueuedCommand(store, sibling, 1L, userMessagePayload("hi"), TestIds.id(2));
    UUID stopRequestId = TestIds.id(1);

    StopResult first = runtime.stop(new StopCommand(sibling, stopRequestId, 0));
    assertFalse(first.replayed());
    assertNull(targetReceipt(first).stoppedTurnEndEntryId());
    assertEquals(1, targetReceipt(first).cancelledCommandCount());
    assertEquals(1L, first.thread().version());

    StopResult replay = runtime.stop(new StopCommand(sibling, stopRequestId, 1L));
    assertReplayed(replay);
    assertNull(targetReceipt(replay).stoppedTurnEndEntryId());
    assertEquals(1, targetReceipt(replay).cancelledCommandCount());
    // 重放不触碰 version。
    assertEquals(1L, replay.thread().version());
    assertEquals(owner.turnStartEntryId(), replay.thread().headEntryId());
  }

  @Test
  void missingThreadIsNotFound() {
    HarnessRuntimeNotFoundException error =
        assertThrows(
            HarnessRuntimeNotFoundException.class,
            () -> runtime.stop(new StopCommand(TestIds.id(999), TestIds.id(1), 0)));
    assertNotNull(error.getMessage());
  }
}
