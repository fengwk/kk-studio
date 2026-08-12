package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedYoloThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
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
    UUID threadId = seedYoloThreadAt(store, chain.turnEndEntryId());
    // 配置 Command 本会被 CONTINUATION 计划应用进 TURN_START settings；Stop 必须取消它而不是应用。
    seedQueuedCommand(
        store, threadId, 1L, new SetAgentCommandPayload("other-agent"), TestIds.id(1));
    seedThreadWork(store, threadId);

    StopResult result = runtime.stop(new StopCommand(threadId, TestIds.id(1), 0));
    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(1, result.cancelledCommandCount());
    assertEquals(1L, result.thread().revision());
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
        store.transaction(tx -> tx.findCommandByClientId(threadId, TestIds.id(1)).orElseThrow());
    assertEquals(ThreadCommandState.CANCELLED, command.state());
    assertNull(command.consumedTurnStartEntryId());
    assertFalse(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)))
            .isPresent());
  }

  @Test
  void continuationStopWithoutCommandsStillClosesTheObligation() {
    HarnessRuntimeTestSupport.ContinuationBaseline chain = seedContinuationChain(store, true);
    StopResult result = runtime.stop(new StopCommand(chain.threadId(), TestIds.id(1), 1));
    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(0, result.cancelledCommandCount());
    ThreadState stored = store.transaction(tx -> tx.lockThread(chain.threadId()).orElseThrow());
    assertEquals(2L, stored.revision());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(stored.headEntryId()));
    assertEquals(8, path.entries().size());
  }
}
