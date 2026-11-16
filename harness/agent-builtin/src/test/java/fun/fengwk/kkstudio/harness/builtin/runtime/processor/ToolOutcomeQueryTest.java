package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.realToolResultPayload;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.resolvedInputTurnStart;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedClosedTurn;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.succeedToolWith;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.tool;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.ToolOutcomeView;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.ClosedTurnBaseline;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.ToolOutcomeQuery;
import fun.fengwk.kkstudio.harness.spi.driver.ToolOutcomeAppender;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 统一结果查询：terminal Invocation 与已物化 Entry 拥有相同投影，且绝不跨 Thread 泄漏。 */
class ToolOutcomeQueryTest {

  @Test
  void terminalInvocationAndMaterializedEntryProjectTheSameOutcome() {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
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

    ToolOutcomeView current = find(store, chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(ToolResultStatus.SUCCEEDED, current.status());
    assertFalse(current.resultError());
    assertEquals(List.of(new TextMessageContent("stable result")), current.contents());
    assertEquals("call-1", current.toolCallId());
    assertEquals(0, current.callIndex());
    assertNull(current.resultEntryId());

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
          tx.tools().lockByAssistantEntry(chain.assistantEntryId());
          tx.tools().deleteByIds(List.of(toolId));
          return null;
        });

    ToolOutcomeView historical = find(store, chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(resultEntryId, historical.resultEntryId());
    assertEquals(current.status(), historical.status());
    assertFalse(historical.resultError());
    assertEquals(current.contents(), historical.contents());
    assertEquals(current.assistantEntryId(), historical.assistantEntryId());
    assertEquals(current.toolCallId(), historical.toolCallId());
    assertEquals(current.callIndex(), historical.callIndex());
  }

  @Test
  void succeededBusinessErrorIsReadableBeforeAndAfterMaterialization() {
    // 正常执行可返回业务错误，物化不能将它误判为损坏的历史身份。
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
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
    ToolOutcomeView current = find(store, chain.turn().threadId(), toolId).orElseThrow();
    assertEquals(ToolResultStatus.SUCCEEDED, current.status());
    assertTrue(current.resultError());
    assertNull(current.error());
    assertEquals(List.of(new TextMessageContent("file not found")), current.contents());
    assertNull(current.resultEntryId());

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
          tx.tools().lockByAssistantEntry(chain.assistantEntryId());
          tx.tools().deleteByIds(List.of(toolId));
          return null;
        });

    ToolOutcomeView historical = find(store, chain.turn().threadId(), toolId).orElseThrow();
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
  }

  @Test
  void nonSucceededHistoryWithFalseErrorFlagIsRejected() {
    // 降低成功分支的限制不能放过非成功终态中损坏的错误标志。
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.FAILED));
    UUID toolId = chain.toolInvocationIds().getFirst();
    ToolOutcomeView live = find(store, chain.turn().threadId(), toolId).orElseThrow();
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
              tx.entries()
                  .insert(new Entry(id, sessionId, chain.assistantEntryId(), corrupted, NOW));
              return id;
            });
    advanceHead(store, chain.turn().threadId(), resultEntryId);
    store.transaction(
        tx -> {
          tx.tools().lockByAssistantEntry(chain.assistantEntryId());
          tx.tools().deleteByIds(List.of(toolId));
          return null;
        });

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> find(store, chain.turn().threadId(), toolId));
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
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    var chain =
        seedToolChain(
            store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));

    assertTrue(
        find(store, chain.turn().threadId(), chain.toolInvocationIds().getFirst()).isEmpty());
  }

  @Test
  void invocationAndMaterializedEntryAreInvisibleToAnotherThread() {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
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

    assertTrue(find(store, other.turn().threadId(), toolId).isEmpty());

    UUID sessionId = path(store, chain.turn().threadId()).root().sessionId();
    var invocation = tool(store, toolId);
    store.transaction(
        tx -> ToolOutcomeAppender.append(tx, sessionId, chain.assistantEntryId(), invocation, NOW));
    store.transaction(
        tx -> {
          tx.tools().lockByAssistantEntry(chain.assistantEntryId());
          tx.tools().deleteByIds(List.of(toolId));
          return null;
        });

    assertTrue(find(store, other.turn().threadId(), toolId).isEmpty());
  }

  @Test
  void sharedParentHistoryDoesNotLeakChildOutcomeToParentOrSiblingFork() {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    ClosedTurnBaseline parent = seedClosedTurn(store, false);
    ChildOutcome first = seedChildToolOutcome(store, parent, "child-1", "call-1");
    ChildOutcome second = seedChildToolOutcome(store, parent, "child-2", "call-2");

    // 父 Thread 的实际 head 路径在共享历史之后结束，不能看到任一子 Thread 自己的结果。
    assertTrue(find(store, parent.threadId(), first.invocationId()).isEmpty());
    assertTrue(find(store, parent.threadId(), second.invocationId()).isEmpty());
    // 同一父历史下的 sibling fork 也不能互相读取对方分支上的结果。
    assertTrue(find(store, first.threadId(), second.invocationId()).isEmpty());
    assertTrue(find(store, second.threadId(), first.invocationId()).isEmpty());
    // 子 Thread 自己的 head 可达路径仍能读到属于它的结果。
    assertEquals(
        "call-1", find(store, first.threadId(), first.invocationId()).orElseThrow().toolCallId());
    assertEquals(
        "call-2", find(store, second.threadId(), second.invocationId()).orElseThrow().toolCallId());
  }

  private static ChildOutcome seedChildToolOutcome(
      InMemoryBuiltinHarnessStore store, ClosedTurnBaseline parent, String name, String callId) {
    return store.transaction(
        tx -> {
          UUID childThreadId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID invocationId = tx.nextId();
          UUID resultEntryId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStartEntryId,
                      parent.sessionId(),
                      parent.turnEndEntryId(),
                      resolvedInputTurnStart(childThreadId),
                      NOW));
          tx.entries()
              .insert(
                  new Entry(
                      userEntryId,
                      parent.sessionId(),
                      turnStartEntryId,
                      userMessagePayload("child input"),
                      NOW));
          tx.entries()
              .insert(
                  new Entry(
                      assistantEntryId,
                      parent.sessionId(),
                      userEntryId,
                      assistantPayload(List.of(callId)),
                      NOW));
          tx.entries()
              .insert(
                  new Entry(
                      resultEntryId,
                      parent.sessionId(),
                      assistantEntryId,
                      realToolResultPayload(invocationId, assistantEntryId, 0, callId),
                      NOW));
          tx.threads()
              .insert(
                  new ThreadState(
                      childThreadId,
                      parent.sessionId(),
                      parent.threadId(),
                      resultEntryId,
                      "a".repeat(64),
                      name,
                      parent.threadId(),
                      false,
                      ThreadKind.SUBAGENT,
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

  /** 测试直接走生产静态入口：先取目标 Thread 快照，再按同一事务读取结果。 */
  private static Optional<ToolOutcomeView> find(
      InMemoryBuiltinHarnessStore store, UUID threadId, UUID invocationId) {
    return store.transaction(
        tx -> ToolOutcomeQuery.find(tx, tx.threads().find(threadId).orElseThrow(), invocationId));
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

  private static void advanceHead(
      InMemoryBuiltinHarnessStore store, UUID threadId, UUID resultEntryId) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElseThrow();
          tx.threads().update(thread.advanceHead(resultEntryId, NOW));
          return null;
        });
  }

  private record ChildOutcome(
      UUID threadId, UUID invocationId, UUID assistantEntryId, UUID resultEntryId) {}
}
