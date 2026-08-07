package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.beginDispatchTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.retryReadyTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.succeedTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Stop 在 TOOL_ACTIVE 上：每个 sibling 按自身状态收敛（WAITING_APPROVAL/READY 转 CANCELLED， DISPATCHING/RUNNING 转
 * UNKNOWN，terminal 保持原样），按 ordinal 的 ToolResult Entry 以 invocation-backed 方式挂载且 synthetic=false，删除
 * THREAD/MODEL 与全部 TOOL Work 行； TOOL_TERMINAL_PENDING 保持零变更。
 */
class HarnessRuntimeStopToolTest {

  private static final String CANCELLED_WORDING =
      "Stopped by user; no further execution or deliverable result is available";
  private static final String UNKNOWN_WORDING =
      "Stopped by user while the tool execution outcome was uncertain";

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void statusMatrixConvergesEverySiblingByItsOwnStatus() {
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(store, 7);
    List<Long> ids = baseline.toolIds();
    inTransaction(
        store,
        tx -> {
          ToolInvocation waiting = tx.lockToolInvocation(ids.get(0)).orElseThrow();
          tx.updateToolInvocations(List.of(waiting.requestApproval("approval needed", T5)));
        });
    beginDispatchTool(store, ids.get(2));
    beginDispatchTool(store, ids.get(3));
    markRunningTool(store, ids.get(3));
    beginDispatchTool(store, ids.get(4));
    markRunningTool(store, ids.get(4));
    succeedTool(store, ids.get(4));
    cancelTool(store, ids.get(5));
    beginDispatchTool(store, ids.get(6));
    markRunningTool(store, ids.get(6));
    retryReadyTool(store, ids.get(6));
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    for (long id : ids) {
      seedToolWork(store, id);
    }

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 1));
    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(0, result.cancelledCommandCount());
    assertEquals(2L, result.thread().revision());

    ThreadState stored = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(stored.headEntryId()));
    assertEquals(12, path.entries().size());
    for (int ordinal = 0; ordinal < ids.size(); ordinal++) {
      Entry entry = path.entries().get(4 + ordinal);
      assertTrue(entry.payload() instanceof MessagePayload);
      ToolResultMetadata metadata = ((MessagePayload) entry.payload()).toolResultMetadata();
      assertEquals(baseline.assistantEntryId(), metadata.assistantEntryId());
      assertEquals("call-" + ordinal, metadata.toolCallId());
      assertEquals(ordinal, metadata.ordinal());
      assertFalse(metadata.synthetic());
      assertEquals(
          ordinal == 4 ? ToolResultStatus.SUCCEEDED : metadataStatus(ordinal), metadata.status());
    }
    Entry turnEnd = path.head();
    assertTrue(turnEnd.payload() instanceof TurnEndPayload);
    TurnEndPayload end = (TurnEndPayload) turnEnd.payload();
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertEquals("STOP/" + baseline.threadId() + "/stop-1", end.closeRequestId());
    assertEquals(baseline.turnStartEntryId(), end.turnStartEntryId());
    assertEquals(turnEnd.id(), stored.headEntryId());

    ToolInvocation waiting = storedTool(ids.get(0));
    assertEquals(ToolInvocationStatus.CANCELLED, waiting.status());
    assertEquals(0, waiting.attempt());
    assertEquals(CANCELLED_WORDING, resultText(waiting));
    assertFalse(CANCELLED_WORDING.contains("never"));
    ToolApproval approval = waiting.approval();
    assertTrue(approval != null && approval.required() && approval.isUndecided());

    ToolInvocation ready = storedTool(ids.get(1));
    assertEquals(ToolInvocationStatus.CANCELLED, ready.status());
    assertEquals(0, ready.attempt());
    assertEquals(CANCELLED_WORDING, resultText(ready));

    ToolInvocation dispatching = storedTool(ids.get(2));
    assertEquals(ToolInvocationStatus.UNKNOWN, dispatching.status());
    assertEquals(1, dispatching.attempt());
    assertEquals(UNKNOWN_WORDING, resultText(dispatching));

    ToolInvocation running = storedTool(ids.get(3));
    assertEquals(ToolInvocationStatus.UNKNOWN, running.status());
    assertEquals(1, running.attempt());
    assertEquals(UNKNOWN_WORDING, resultText(running));

    ToolInvocation succeeded = storedTool(ids.get(4));
    assertEquals(ToolInvocationStatus.SUCCEEDED, succeeded.status());
    assertEquals(1, succeeded.attempt());
    assertEquals("real result", resultText(succeeded));

    ToolInvocation cancelled = storedTool(ids.get(5));
    assertEquals(ToolInvocationStatus.CANCELLED, cancelled.status());
    assertEquals(0, cancelled.attempt());
    assertEquals("cancelled", resultText(cancelled));

    ToolInvocation retryReady = storedTool(ids.get(6));
    assertEquals(ToolInvocationStatus.CANCELLED, retryReady.status());
    assertEquals(1, retryReady.attempt());
    assertEquals(CANCELLED_WORDING, resultText(retryReady));

    // 每个 sibling 的 resultEntryId 精确指向自己的 ordinal ToolResult Entry。
    for (int ordinal = 0; ordinal < ids.size(); ordinal++) {
      assertEquals(
          path.entries().get(4 + ordinal).id(), storedTool(ids.get(ordinal)).resultEntryId());
    }

    // 全部 Work 删除：THREAD、owning MODEL 与每个 sibling TOOL。
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
    for (long id : ids) {
      assertFalse(
          store
              .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, id)))
              .isPresent());
    }

    // Model 行未被 Stop 触碰：SUCCEEDED 且结果仍挂在 assistant。
    ModelInvocation model =
        store.transaction(tx -> tx.findModelInvocation(baseline.modelId()).orElseThrow());
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(baseline.assistantEntryId(), model.resultEntryId());
  }

  @Test
  void terminalPendingToolStopConflictsWithoutAnyMutation() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    cancelTool(store, baseline);
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    seedToolWork(store, baseline.toolId());
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 1)));
    assertEquals(Reason.TERMINAL_APPLY_PENDING, error.reason());
    ToolInvocation tool =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.CANCELLED, tool.status());
    assertNull(tool.resultEntryId());
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(1L, thread.revision());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    assertEquals(4, path.entries().size());
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
    assertTrue(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())))
            .isPresent());
  }

  private ToolInvocation storedTool(long toolId) {
    return store.transaction(tx -> tx.findToolInvocation(toolId).orElseThrow());
  }

  /** invocation 追加的 ToolResult MESSAGE entry 中唯一的文本块。 */
  private String resultText(ToolInvocation tool) {
    long resultEntryId = tool.resultEntryId();
    Entry entry = store.transaction(tx -> tx.findEntry(resultEntryId).orElseThrow());
    MessagePayload payload = (MessagePayload) entry.payload();
    ToolResultMessageContent content =
        (ToolResultMessageContent) payload.message().contents().get(0);
    AgentMessageContent text = content.contents().get(0);
    return ((TextMessageContent) text).text();
  }

  private static ToolResultStatus metadataStatus(int ordinal) {
    return switch (ordinal) {
      case 0, 1, 5, 6 -> ToolResultStatus.CANCELLED;
      case 2, 3 -> ToolResultStatus.UNKNOWN;
      default -> throw new IllegalArgumentException("unexpected ordinal " + ordinal);
    };
  }
}
