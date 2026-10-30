package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.realToolResultPayload;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.resolvedInputTurnStart;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.succeedToolWith;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tool;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport;
import fun.fengwk.kkstudio.harness.runtime.ToolOutcomeView;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.ClosedTurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.UUID;

/** 统一结果查询：terminal Invocation 与已物化 Entry 拥有相同投影，且绝不跨 Thread 泄漏。 */
class ToolOutcomeQueryTest {

  @Test
  void terminalInvocationAndMaterializedEntryProjectTheSameOutcome() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    UUID toolId = chain.toolInvocationIds().getFirst();
    succeedToolWith(
        store,
        toolId,
        new ToolResult("call-1", List.of(new TextResultContent("stable result")), false, "{}"));
    HarnessRuntime runtime = runtime(store);

    ToolOutcomeView current =
        runtime.findToolOutcome(chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(ToolResultStatus.SUCCEEDED, current.status());
    assertFalse(current.resultError());
    assertEquals(List.of(new TextMessageContent("stable result")), current.contents());
    assertEquals("call-1", current.toolCallId());
    assertEquals(0, current.callIndex());
    assertFalse(current.materialized());

    UUID sessionId = path(store, chain.turn().threadId()).root().sessionId();
    var invocation = tool(store, toolId);
    UUID resultEntryId =
        store.transaction(
            tx ->
                ToolOutcomeAppender.append(tx, sessionId, chain.assistantEntryId(), invocation, NOW)
                    .headEntryId());
    advanceHead(store, chain.turn().threadId(), resultEntryId);
    store.transaction(
        tx -> {
          tx.lockToolInvocationsByAssistantEntryId(chain.assistantEntryId());
          tx.deleteToolInvocationsByIds(List.of(toolId));
          return null;
        });

    ToolOutcomeView historical =
        runtime.findToolOutcome(chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(resultEntryId, historical.resultEntryId());
    assertEquals(current.status(), historical.status());
    assertFalse(historical.resultError());
    assertEquals(current.contents(), historical.contents());
    assertEquals(current.assistantEntryId(), historical.assistantEntryId());
    assertEquals(current.toolCallId(), historical.toolCallId());
    assertEquals(current.callIndex(), historical.callIndex());
    assertTrue(historical.materialized());
  }

  @Test
  void succeededBusinessErrorIsReadableBeforeAndAfterMaterialization() {
    // 正常执行可返回业务错误，物化不能将它误判为损坏的历史身份。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    UUID toolId = chain.toolInvocationIds().getFirst();
    succeedToolWith(
        store,
        toolId,
        new ToolResult("call-1", List.of(new TextResultContent("file not found")), true, "{}"));
    HarnessRuntime runtime = runtime(store);
    ToolOutcomeView current =
        runtime.findToolOutcome(chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(ToolResultStatus.SUCCEEDED, current.status());
    assertTrue(current.resultError());
    assertNull(current.error());
    assertEquals(List.of(new TextMessageContent("file not found")), current.contents());
    assertFalse(current.materialized());

    UUID sessionId = path(store, chain.turn().threadId()).root().sessionId();
    var invocation = tool(store, toolId);
    UUID resultEntryId =
        store.transaction(
            tx ->
                ToolOutcomeAppender.append(tx, sessionId, chain.assistantEntryId(), invocation, NOW)
                    .headEntryId());
    advanceHead(store, chain.turn().threadId(), resultEntryId);
    store.transaction(
        tx -> {
          tx.lockToolInvocationsByAssistantEntryId(chain.assistantEntryId());
          tx.deleteToolInvocationsByIds(List.of(toolId));
          return null;
        });

    ToolOutcomeView historical =
        runtime.findToolOutcome(chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(ToolResultStatus.SUCCEEDED, historical.status());
    assertTrue(historical.resultError());
    assertNull(historical.error());
    assertEquals(current.invocationId(), historical.invocationId());
    assertEquals(current.threadId(), historical.threadId());
    assertEquals(current.assistantEntryId(), historical.assistantEntryId());
    assertEquals(current.callIndex(), historical.callIndex());
    assertEquals(current.inputReceipt(), historical.inputReceipt());
    assertEquals(current.contents(), historical.contents());
    assertEquals(current.toolCallId(), historical.toolCallId());
    assertEquals(resultEntryId, historical.resultEntryId());
    assertTrue(historical.materialized());
  }

  @Test
  void nonSucceededHistoryWithFalseErrorFlagIsRejected() {
    // 降低成功分支的限制不能放过非成功终态中损坏的错误标志。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.FAILED));
    UUID toolId = chain.toolInvocationIds().getFirst();
    ToolOutcomeView live =
        runtime(store).findToolOutcome(chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(ToolResultStatus.FAILED, live.status());
    assertTrue(live.resultError());
    assertEquals(new ToolInvocationError("FAILED", "tool failed"), live.error());
    MessagePayload valid = new HistoryPayloadMapper().toolResultPayload(tool(store, toolId));
    ToolResultMessageContent result =
        (ToolResultMessageContent) valid.message().contents().getFirst();
    MessagePayload corrupted =
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.TOOL,
                List.of(
                    new ToolResultMessageContent(
                        result.toolCallId(),
                        result.toolName(),
                        result.rendererKey(),
                        result.contents(),
                        false,
                        result.detailsJson()))),
            null,
            valid.toolResultMetadata());
    UUID sessionId = path(store, chain.turn().threadId()).root().sessionId();
    UUID resultEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(new Entry(id, sessionId, chain.assistantEntryId(), corrupted, NOW));
              return id;
            });
    advanceHead(store, chain.turn().threadId(), resultEntryId);
    store.transaction(
        tx -> {
          tx.lockToolInvocationsByAssistantEntryId(chain.assistantEntryId());
          tx.deleteToolInvocationsByIds(List.of(toolId));
          return null;
        });

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> runtime(store).findToolOutcome(chain.turn().threadId(), toolId));
    assertTrue(error.getMessage().contains("does not match its assistant tool call"));
  }

  @Test
  void viewRequiresLifecycleFailuresToCarryBothErrorFacts() {
    // 业务错误与生命周期独立，但非成功终态不能丢失标志或结构化错误。
    ToolInvocationError error = new ToolInvocationError("FAILED", "tool failed");
    assertThrows(IllegalArgumentException.class, () -> view(ToolResultStatus.FAILED, false, error));
    assertThrows(IllegalArgumentException.class, () -> view(ToolResultStatus.FAILED, true, null));
    assertThrows(
        IllegalArgumentException.class, () -> view(ToolResultStatus.SUCCEEDED, true, error));
    assertTrue(view(ToolResultStatus.FAILED, true, error).resultError());
  }

  @Test
  void nonTerminalInvocationIsNotAnOutcome() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));

    assertTrue(
        runtime(store)
            .findToolOutcome(chain.turn().threadId(), chain.toolInvocationIds().getFirst())
            .isEmpty());
  }

  @Test
  void invocationAndMaterializedEntryAreInvisibleToAnotherThread() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    UUID toolId = chain.toolInvocationIds().getFirst();
    var other =
        seedToolChain(
            store,
            List.of("call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    succeedToolWith(
        store,
        toolId,
        new ToolResult("call-1", List.of(new TextResultContent("stable result")), false, "{}"));
    HarnessRuntime runtime = runtime(store);

    assertTrue(runtime.findToolOutcome(other.turn().threadId(), toolId).isEmpty());

    UUID sessionId = path(store, chain.turn().threadId()).root().sessionId();
    var invocation = tool(store, toolId);
    store.transaction(
        tx -> ToolOutcomeAppender.append(tx, sessionId, chain.assistantEntryId(), invocation, NOW));
    store.transaction(
        tx -> {
          tx.lockToolInvocationsByAssistantEntryId(chain.assistantEntryId());
          tx.deleteToolInvocationsByIds(List.of(toolId));
          return null;
        });

    assertTrue(runtime.findToolOutcome(other.turn().threadId(), toolId).isEmpty());
  }

  @Test
  void sharedParentHistoryDoesNotLeakChildOutcomeToParentOrSiblingFork() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ClosedTurnBaseline parent = seedClosedTurn(store, false);
    ChildOutcome first = seedChildToolOutcome(store, parent, "child-1", "call-1");
    ChildOutcome second = seedChildToolOutcome(store, parent, "child-2", "call-2");
    HarnessRuntime runtime = runtime(store);

    // 父 Thread 的实际 head 路径在共享历史之后结束，不能看到任一子 Thread 自己的结果。
    assertTrue(runtime.findToolOutcome(parent.threadId(), first.invocationId()).isEmpty());
    assertTrue(runtime.findToolOutcome(parent.threadId(), second.invocationId()).isEmpty());
    // 同一父历史下的 sibling fork 也不能互相读取对方分支上的结果。
    assertTrue(runtime.findToolOutcome(first.threadId(), second.invocationId()).isEmpty());
    assertTrue(runtime.findToolOutcome(second.threadId(), first.invocationId()).isEmpty());
    // 子 Thread 自己的 head 可达路径仍能读到属于它的结果。
    assertEquals(
        "call-1",
        runtime.findToolOutcome(first.threadId(), first.invocationId()).orElseThrow().toolCallId());
    assertEquals(
        "call-2",
        runtime
            .findToolOutcome(second.threadId(), second.invocationId())
            .orElseThrow()
            .toolCallId());
  }

  private static ChildOutcome seedChildToolOutcome(
      InMemoryHarnessStore store, ClosedTurnBaseline parent, String name, String callId) {
    return store.transaction(
        tx -> {
          UUID childThreadId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID invocationId = tx.nextId();
          UUID resultEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  parent.sessionId(),
                  parent.turnEndEntryId(),
                  resolvedInputTurnStart(childThreadId),
                  NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  parent.sessionId(),
                  turnStartEntryId,
                  userMessagePayload("child input"),
                  NOW));
          tx.insertEntry(
              new Entry(
                  assistantEntryId,
                  parent.sessionId(),
                  userEntryId,
                  assistantPayload(List.of(callId)),
                  NOW));
          tx.insertEntry(
              new Entry(
                  resultEntryId,
                  parent.sessionId(),
                  assistantEntryId,
                  realToolResultPayload(invocationId, assistantEntryId, 0, callId),
                  NOW));
          tx.insertThread(
              new ThreadState(
                  childThreadId,
                  parent.sessionId(),
                  parent.threadId(),
                  resultEntryId,
                  "a".repeat(64),
                  name,
                  parent.threadId(),
                  false,
                  SpawnTrigger.MODEL_TOOL,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  NOW,
                  NOW));
          return new ChildOutcome(childThreadId, invocationId, assistantEntryId, resultEntryId);
        });
  }

  private static ToolOutcomeView view(
      ToolResultStatus status, boolean resultError, ToolInvocationError error) {
    return new ToolOutcomeView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "call-1",
        0,
        status,
        resultError,
        List.of(new TextMessageContent("result")),
        error,
        null,
        null);
  }

  private static void advanceHead(InMemoryHarnessStore store, UUID threadId, UUID resultEntryId) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(thread.advanceHead(resultEntryId, NOW));
          return null;
        });
  }

  private record ChildOutcome(
      UUID threadId, UUID invocationId, UUID assistantEntryId, UUID resultEntryId) {}

  private static HarnessRuntime runtime(InMemoryHarnessStore store) {
    return new HarnessRuntime(
        store,
        HarnessRuntimeTestSupport.driver(store, (threadId, path, preparation) -> null),
        () -> CompactionConfig.DEFAULT);
  }
}
