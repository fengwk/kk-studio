package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stop 在 CONTINUATION_DUE 上：恰好三条 Entry（TURN_START(CONTINUATION, baseSettings)、
 * ASSISTANT_ERROR(CANCELLED)、带复合 key 的 STOPPED TURN_END），无 ModelInvocation， 不应用 queued config，所有
 * Command 被取消，且冻结的 YOLO 策略被保留。
 */
class HarnessRuntimeStopContinuationTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void continuationStopAppendsExactlyThreeEntriesWithBaseSettingsAndCancelsConfigCommands() {
    HarnessRuntimeTestSupport.ContinuationBaseline chain = seedContinuationChain(store, true);
    // chain.threadId 是 owner-aware 分类器下该 CONTINUATION obligation 的唯一 owner；直接在其上冻结 YOLO。
    UUID threadId = chain.threadId();
    runtime.setThreadYolo(new SetThreadYoloCommand(threadId, 1, true));
    // 配置 Command 本会被 CONTINUATION 计划应用进 TURN_START settings；Stop 必须取消它而不是应用。
    seedQueuedCommand(
        store, threadId, 1L, new SetAgentCommandPayload("other-agent"), TestIds.id(1));
    seedThreadWork(store, threadId);

    StopResult result = runtime.stop(new StopCommand(threadId, TestIds.id(1), 2));
    assertStopped(result);
    assertEquals(1, result.cancelledCommandCount());
    assertEquals(3L, result.thread().version());
    assertTrue(result.thread().yoloEnabled());

    ThreadState stored = store.transaction(tx -> tx.lockThread(threadId).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(stored.headEntryId()));
    assertEquals(8, path.entries().size());
    Entry turnStart = path.entries().get(5);
    Entry barrier = path.entries().get(6);
    Entry turnEnd = path.entries().get(7);
    assertTrue(turnStart.payload() instanceof TurnStartPayload);
    TurnStartPayload startPayload = (TurnStartPayload) turnStart.payload();
    assertEquals(TurnStartReason.CONTINUATION, startPayload.reason());
    // baseSettings 原样进入新的 TURN_START：queued config 未被应用。
    assertEquals(settings(), startPayload.settings());
    assertEquals(chain.turnEndEntryId(), turnStart.parentEntryId());
    assertTrue(barrier.payload() instanceof AssistantErrorPayload);
    AssistantError barrierError = ((AssistantErrorPayload) barrier.payload()).error();
    assertEquals("CANCELLED", barrierError.code());
    assertEquals(turnStart.id(), barrier.parentEntryId());
    assertTrue(turnEnd.payload() instanceof TurnEndPayload);
    TurnEndPayload endPayload = (TurnEndPayload) turnEnd.payload();
    assertEquals(TurnEndOutcome.STOPPED, endPayload.outcome());
    assertEquals(TurnEndReason.USER_STOP, endPayload.reason());
    assertEquals(TestIds.id(1), endPayload.closeRequestId());
    assertEquals(turnStart.id(), endPayload.turnStartEntryId());
    assertEquals(barrier.id(), turnEnd.parentEntryId());
    assertEquals(turnEnd.id(), stored.headEntryId());

    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByIdempotencyKey(threadId, TestIds.id(1)).orElseThrow());
    assertEquals(ThreadCommandState.CANCELLED, command.state());
    assertNull(command.appliedTurnStartEntryId());
    assertFalse(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)))
            .isPresent());
  }

  @Test
  void continuationStopWithoutCommandsStillClosesTheObligation() {
    HarnessRuntimeTestSupport.ContinuationBaseline chain = seedContinuationChain(store, true);
    StopResult result = runtime.stop(new StopCommand(chain.threadId(), TestIds.id(1), 1));
    assertStopped(result);
    assertEquals(0, result.cancelledCommandCount());
    ThreadState stored = store.transaction(tx -> tx.lockThread(chain.threadId()).orElseThrow());
    assertEquals(2L, stored.version());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(stored.headEntryId()));
    assertEquals(8, path.entries().size());
  }

  @Test
  void stopClosesOwnedHistoryPhaseGapWithExactCompactionBarrier() {
    // HISTORY 成功后的 continueModel obligation 必须可被 Stop durable 关闭，不能只依赖删除 THREAD Work。
    UUID[] ids =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              UUID inputStartId = tx.nextId();
              UUID userId = tx.nextId();
              UUID assistantId = tx.nextId();
              UUID inputEndId = tx.nextId();
              UUID historyStartId = tx.nextId();
              UUID historyResultId = tx.nextId();
              UUID historyEndId = tx.nextId();
              UUID threadId = tx.nextId();
              tx.insertSession(HarnessRuntimeTestSupport.session(sessionId));
              tx.insertEntry(HarnessRuntimeTestSupport.rootEntry(rootId, sessionId));
              tx.insertEntry(
                  HarnessRuntimeTestSupport.turnStartEntry(
                      inputStartId, sessionId, rootId, T5, threadId));
              tx.insertEntry(
                  HarnessRuntimeTestSupport.userMessageEntry(userId, sessionId, inputStartId, T5));
              tx.insertEntry(
                  HarnessRuntimeTestSupport.assistantEntry(assistantId, sessionId, userId, T5));
              tx.insertEntry(
                  new Entry(
                      inputEndId,
                      sessionId,
                      assistantId,
                      new TurnEndPayload(inputStartId, TurnEndOutcome.COMPLETED, false, null, null),
                      T5));
              CompactionStart history =
                  new CompactionStart(
                      CompactionPhase.HISTORY,
                      CompactionTrigger.THRESHOLD,
                      settings().model(),
                      assistantId,
                      userId,
                      null);
              tx.insertEntry(
                  new Entry(
                      historyStartId,
                      sessionId,
                      inputEndId,
                      new TurnStartPayload(
                          TurnStartReason.COMPACTION,
                          settings(),
                          threadId,
                          100_000,
                          16_384,
                          history),
                      T5));
              tx.insertEntry(
                  new Entry(
                      historyResultId,
                      sessionId,
                      historyStartId,
                      new CompactionPayload("history summary"),
                      T5));
              tx.insertEntry(
                  new Entry(
                      historyEndId,
                      sessionId,
                      historyResultId,
                      new TurnEndPayload(
                          historyStartId, TurnEndOutcome.COMPLETED, true, null, null),
                      T5));
              tx.insertThread(HarnessRuntimeTestSupport.thread(threadId, sessionId, historyEndId));
              return new UUID[] {threadId, historyEndId, historyResultId, assistantId, userId};
            });

    UUID stopRequestId = TestIds.id(99);
    StopResult first = runtime.stop(new StopCommand(ids[0], stopRequestId, 0));
    assertStopped(first);
    ThreadState stored = store.transaction(tx -> tx.lockThread(ids[0]).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(stored.headEntryId()));
    assertEquals(11, path.entries().size());
    TurnStartPayload barrierStart = (TurnStartPayload) path.entries().get(8).payload();
    assertEquals(TurnStartReason.COMPACTION, barrierStart.reason());
    assertEquals(CompactionPhase.TURN_PREFIX, barrierStart.compaction().phase());
    assertEquals(ids[3], barrierStart.compaction().cutEntryId());
    assertEquals(ids[4], barrierStart.compaction().turnPrefixStartEntryId());
    assertEquals(ids[2], barrierStart.compaction().historyCompactionEntryId());
    TurnEndPayload barrierEnd = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.STOPPED, barrierEnd.outcome());
    assertEquals(stopRequestId, barrierEnd.closeRequestId());

    StopResult replay = runtime.stop(new StopCommand(ids[0], stopRequestId, 0));
    assertTrue(replay.replayed());
    assertEquals(first.stoppedTurnEndEntryId(), replay.stoppedTurnEndEntryId());
  }
}
