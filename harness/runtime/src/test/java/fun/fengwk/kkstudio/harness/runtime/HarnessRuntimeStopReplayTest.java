package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertIdle;
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
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
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
 * 的 queued 取消以 (threadId, cancelRequestId) 作幂等键。
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
  void firstStopThenReplayReturnsTheOriginalStoppedTurnEnd() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(first);
    UUID turnEndId = first.stoppedTurnEndEntryId();

    // 重放先于 version 检查：即使 expectedVersion 已过期也返回 replayed，且不取消任何 Command。
    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertReplayed(replay);
    assertEquals(turnEndId, replay.stoppedTurnEndEntryId());
    assertEquals(0, replay.cancelledCommandCount());
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
    seedThreadWork(store, threadId);
    seedModelWork(store, modelId);

    // replay 先于 version CAS：即使 expectedVersion 已过期，也返回原 STOPPED TURN_END 且零 mutation。
    StopResult replay = runtime.stop(new StopCommand(threadId, key, 9));
    assertReplayed(replay);
    assertEquals(turnEnd1, replay.stoppedTurnEndEntryId());
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

    // sibling 的上下文是 foreign CONTINUATION → IDLE_OR_HISTORICAL：同一 raw id 不 replay、不冲突，Stop 是零
    // mutation 的 IDLE。
    StopResult siblingResult = runtime.stop(new StopCommand(sibling, TestIds.id(1), 0));
    assertIdle(siblingResult);
    assertEquals(0, siblingResult.cancelledCommandCount());
    assertEquals(0L, siblingResult.thread().version());

    // owner 自己的 key 仍是精确重放（version 已过期也返回 REPLAYED）。
    StopResult ownerReplay = runtime.stop(new StopCommand(chain.threadId(), TestIds.id(1), 0));
    assertReplayed(ownerReplay);
    assertEquals(owner.stoppedTurnEndEntryId(), ownerReplay.stoppedTurnEndEntryId());
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
    assertNotNull(first.stoppedTurnEndEntryId());
    assertEquals(1, first.cancelledCommandCount());
    assertEquals(1, first.cancelledUserMessages().size());

    // transport 丢失：同一 stopRequestId 重试（replay 先于 version CAS，expectedVersion 过期也无碍）。
    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(9), 0));
    assertReplayed(replay);
    assertEquals(first.stoppedTurnEndEntryId(), replay.stoppedTurnEndEntryId());
    assertEquals(first.cancelledCommandCount(), replay.cancelledCommandCount());
    assertEquals(first.cancelledUserMessages(), replay.cancelledUserMessages());
    // replay 不写任何新 marker：version 与 head 保持首次 Stop 后的终态。
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(replay.thread().version(), thread.version());
    assertEquals(replay.stoppedTurnEndEntryId(), thread.headEntryId());
    // 取消行仍以该 stopRequestId 持久化（queued-only 维度与 live receipt 一致）。
    assertEquals(
        1,
        store
            .transaction(
                tx -> tx.loadCancelledCommandsByRequest(baseline.threadId(), TestIds.id(9)))
            .size());
  }

  @Test
  void exactKeyOnNonStoppedCloseConflicts() {
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
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(threadId, TestIds.id(1), 0)));
    assertEquals(Reason.STOP_REQUEST_ID_REUSED, error.reason());
  }

  @Test
  void duplicateSameOwnerKeyIsAnInvariantViolation() {
    UUID threadId = TestIds.id(12);
    UUID sessionId = TestIds.id(22);
    UUID rootId = TestIds.id(32);
    UUID turnStart1 = TestIds.id(42);
    UUID turnEnd1 = TestIds.id(72);
    UUID turnStart2 = TestIds.id(43);
    UUID turnEnd2 = TestIds.id(73);
    UUID user1 = TestIds.id(51);
    UUID assistant1 = TestIds.id(61);
    UUID user2 = TestIds.id(52);
    UUID assistant2 = TestIds.id(62);
    UUID key = TestIds.id(1);
    inTransaction(
        store,
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          // 两个 TURN_START 同属 threadId：同 key 的 STOPPED 在同一 owner 下重复即持久化不变量破坏。
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
          tx.insertEntry(assistantEntry(assistant2, sessionId, user2, T1));
          tx.insertEntry(
              new Entry(
                  turnEnd2,
                  sessionId,
                  assistant2,
                  new TurnEndPayload(
                      turnStart2, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, key),
                  T1));
          tx.insertThread(thread(threadId, sessionId, turnEnd2));
        });
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> runtime.stop(new StopCommand(threadId, TestIds.id(1), 0)));
    assertTrue(error.getMessage().contains("more than once"));
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
  void idleRepeatIsIdleNotReplayed() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertIdle(first);
    // idle Stop 不写 Entry 且没有可取消的 Command，因此同 key 再次请求仍是 IDLE（没有可重放的 receipt）。
    StopResult second = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertIdle(second);
    assertEquals(0L, second.thread().version());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(baseline.rootEntryId()));
    assertEquals(1, path.entries().size());
  }

  /** queued 取消 receipt：未创建 Turn 的 Stop 以 (threadId, cancelRequestId) 作幂等键，重试 returns replayed。 */
  @Test
  void idleCommandRetryReplaysTheQueuedOnlyCancelReceipt() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("hi"), TestIds.id(1));
    seedThreadWork(store, baseline.threadId());
    StopResult first = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertIdle(first);
    assertEquals(1, first.cancelledCommandCount());
    assertEquals(
        List.of(new CancelledUserMessage(1L, TestIds.id(1), List.of(new TextMessageContent("hi")))),
        first.cancelledUserMessages());
    assertEquals(1L, first.thread().version());

    // 同一网络重试命中 queued-only receipt：返回 replayed，不再做第二次取消。
    StopResult replay = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertReplayed(replay);
    assertNull(replay.stoppedTurnEndEntryId());
    assertEquals(1, replay.cancelledCommandCount());
    assertEquals(first.cancelledUserMessages(), replay.cancelledUserMessages());
    assertEquals(1L, replay.thread().version());
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
