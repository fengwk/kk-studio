package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedRunningContinuationModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedTerminalModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Stop on MODEL_ACTIVE: CANCELLED with attempt semantics per status, checkpoint barriers
 * (ASSISTANT_ABORTED thinking-then-text or ASSISTANT_ERROR(CANCELLED)), one STOPPED TURN_END with
 * the composite key, THREAD+MODEL Work deletion, and zero mutation for MODEL_TERMINAL_PENDING.
 */
class HarnessRuntimeStopModelTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void readyModelStopWritesErrorBarrierKeepsAttemptAndCancelsCommands() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.READY);
    seedQueuedCommand(store, baseline.threadId(), 1L, 1L, userMessagePayload("hi"), "cid-1");
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(1, result.cancelledCommandCount());
    ModelInvocation model = storedModel(baseline.modelId());
    assertEquals(ModelInvocationStatus.CANCELLED, model.status());
    assertEquals(0, model.attempt());
    assertNull(model.streamCheckpoint());

    EntryPath path = pathOf(baseline.threadId());
    assertEquals(5, path.entries().size());
    Entry barrier = path.entries().get(3);
    Entry turnEnd = path.entries().get(4);
    assertTrue(barrier.payload() instanceof AssistantErrorPayload);
    AssistantError error = ((AssistantErrorPayload) barrier.payload()).error();
    assertEquals("CANCELLED", error.code());
    assertEquals(barrier.id(), model.resultEntryId());
    assertStoppedEnd(turnEnd, path, barrier, baseline.threadId(), "stop-1");

    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), "cid-1").orElseThrow());
    assertEquals(ThreadCommandState.CANCELLED, command.state());
    assertFalse(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
    assertFalse(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, baseline.modelId())))
            .isPresent());
  }

  @Test
  void dispatchingModelStopAdvancesAttemptByOne() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.DISPATCHING);
    runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    ModelInvocation model = storedModel(baseline.modelId());
    assertEquals(ModelInvocationStatus.CANCELLED, model.status());
    assertEquals(1, model.attempt());
  }

  @Test
  void runningModelStopKeepsTheConfirmedAttempt() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    ModelInvocation model = storedModel(baseline.modelId());
    assertEquals(ModelInvocationStatus.CANCELLED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.CANCELLED, model.error().kind());
  }

  @Test
  void checkpointBarrierPreservesThinkingThenTextWithoutTrim() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(baseline.modelId()).orElseThrow();
          tx.updateModelInvocation(
              model.checkpoint(
                  new StreamCheckpoint(1, 1, "partial text", "  partial thinking  "), T5));
        });
    runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    ModelInvocation model = storedModel(baseline.modelId());
    assertEquals(ModelInvocationStatus.CANCELLED, model.status());
    assertNull(model.streamCheckpoint());

    EntryPath path = pathOf(baseline.threadId());
    Entry barrier = path.entries().get(3);
    assertTrue(barrier.payload() instanceof AssistantAbortedPayload);
    AssistantAbortedPayload aborted = (AssistantAbortedPayload) barrier.payload();
    List<AgentMessageContent> contents = aborted.message().contents();
    assertEquals(2, contents.size());
    assertTrue(contents.get(0) instanceof ThinkingMessageContent);
    assertEquals("  partial thinking  ", ((ThinkingMessageContent) contents.get(0)).text());
    assertTrue(contents.get(1) instanceof TextMessageContent);
    assertEquals("partial text", ((TextMessageContent) contents.get(1)).text());
    assertEquals(barrier.id(), model.resultEntryId());
  }

  @Test
  void checkpointWithOnlyThinkingOrOnlyTextProducesASingleBlockBarrier() {
    HarnessRuntimeTestSupport.ModelBaseline thinkingOnly =
        seedModel(store, ModelInvocationStatus.RUNNING);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(thinkingOnly.modelId()).orElseThrow();
          tx.updateModelInvocation(
              model.checkpoint(new StreamCheckpoint(1, 1, "", "only thinking"), T5));
        });
    runtime.stop(new StopCommand(thinkingOnly.threadId(), "stop-1", 0));
    EntryPath thinkingPath = pathOf(thinkingOnly.threadId());
    AssistantAbortedPayload thinkingBarrier =
        (AssistantAbortedPayload) thinkingPath.entries().get(3).payload();
    assertEquals(1, thinkingBarrier.message().contents().size());
    assertTrue(thinkingBarrier.message().contents().get(0) instanceof ThinkingMessageContent);

    HarnessRuntimeTestSupport.ModelBaseline textOnly =
        seedModel(store, ModelInvocationStatus.RUNNING);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(textOnly.modelId()).orElseThrow();
          tx.updateModelInvocation(
              model.checkpoint(new StreamCheckpoint(1, 1, "only text", ""), T5));
        });
    runtime.stop(new StopCommand(textOnly.threadId(), "stop-1", 0));
    EntryPath textPath = pathOf(textOnly.threadId());
    AssistantAbortedPayload textBarrier =
        (AssistantAbortedPayload) textPath.entries().get(3).payload();
    assertEquals(1, textBarrier.message().contents().size());
    assertTrue(textBarrier.message().contents().get(0) instanceof TextMessageContent);
  }

  /** 真实 CONTINUATION turn 的 Model stop：barrier 直接挂在无 input 的 TURN_START 之下。 */
  @Test
  void continuationTurnModelStopAppendsBarrierUnderTheBareTurnStart() {
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedRunningContinuationModel(store);
    seedModelWork(store, baseline.modelId());
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.STOPPED, result.status());
    EntryPath path = pathOf(baseline.threadId());
    assertEquals(4, path.entries().size());
    Entry barrier = path.entries().get(2);
    assertTrue(barrier.payload() instanceof AssistantErrorPayload);
    assertEquals(baseline.turnStartEntryId(), barrier.parentEntryId());
    assertFalse(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, baseline.modelId())))
            .isPresent());
  }

  @Test
  void terminalPendingModelStopConflictsWithoutAnyMutation() {
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedTerminalModel(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, 1L, userMessagePayload("hi"), "cid-1");
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0)));
    assertEquals(Reason.TERMINAL_APPLY_PENDING, error.reason());
    ModelInvocation model = storedModel(baseline.modelId());
    assertTrue(model.status().isTerminal());
    assertNull(model.resultEntryId());
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, thread.revision());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), "cid-1").orElseThrow());
    assertEquals(ThreadCommandState.QUEUED, command.state());
    assertTrue(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
    assertTrue(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, baseline.modelId())))
            .isPresent());
  }

  private ModelInvocation storedModel(long modelId) {
    return store.transaction(tx -> tx.findModelInvocation(modelId).orElseThrow());
  }

  private EntryPath pathOf(long threadId) {
    ThreadState thread = store.transaction(tx -> tx.lockThread(threadId).orElseThrow());
    return store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
  }

  private static void assertStoppedEnd(
      Entry turnEnd, EntryPath path, Entry barrier, long threadId, String stopRequestId) {
    assertTrue(turnEnd.payload() instanceof TurnEndPayload);
    TurnEndPayload end = (TurnEndPayload) turnEnd.payload();
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertEquals("STOP/" + threadId + "/" + stopRequestId, end.closeRequestId());
    assertEquals(path.entries().get(1).id(), end.turnStartEntryId());
    assertEquals(barrier.id(), turnEnd.parentEntryId());
    assertEquals(turnEnd.id(), path.head().id());
  }
}
