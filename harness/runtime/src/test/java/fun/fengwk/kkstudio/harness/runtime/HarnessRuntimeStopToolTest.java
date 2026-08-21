package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
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
    List<UUID> ids = baseline.toolIds();
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
    ToolEffectBatch effects =
        new ToolEffectBatch(
            List.of(new CustomEntryPayload("goal", "state", 1, "{\"status\":\"active\"}")));
    succeedTool(store, ids.get(4), effects);
    cancelTool(store, ids.get(5));
    beginDispatchTool(store, ids.get(6));
    markRunningTool(store, ids.get(6));
    retryReadyTool(store, ids.get(6));
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    for (UUID id : ids) {
      seedToolWork(store, id);
    }

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));
    assertStopped(result);
    assertEquals(0, result.cancelledCommandCount());
    assertEquals(2L, result.thread().version());

    ThreadState stored = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(stored.headEntryId()));
    assertEquals(13, path.entries().size());
    assertEquals(
        new CustomEntryPayload("goal", "state", 1, "{\"status\":\"active\"}"),
        path.entries().get(8).payload());
    for (int ordinal = 0; ordinal < ids.size(); ordinal++) {
      Entry entry = path.entries().get(resultEntryIndex(ordinal));
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
    assertEquals(TestIds.id(1), end.closeRequestId());
    assertEquals(baseline.turnStartEntryId(), end.turnStartEntryId());
    assertEquals(turnEnd.id(), stored.headEntryId());

    // 每个 sibling 的 ToolResult Entry 内容由其前置状态收敛（WAITING_APPROVAL/READY → CANCELLED，
    // DISPATCHING/RUNNING → UNKNOWN，SUCCEEDED → SUCCEEDED）；attempt 不再 durable（行被物理删除），
    // 只能通过 Entry metadata.status + 内容文本验证状态收敛。
    assertFalse(CANCELLED_WORDING.contains("never"));
    String[] expectedTexts = {
      CANCELLED_WORDING, // 0 WAITING_APPROVAL -> CANCELLED
      CANCELLED_WORDING, // 1 READY -> CANCELLED
      UNKNOWN_WORDING, // 2 DISPATCHING -> UNKNOWN
      UNKNOWN_WORDING, // 3 RUNNING -> UNKNOWN
      "real result", // 4 SUCCEEDED -> SUCCEEDED
      "cancelled", // 5 CANCELLED -> CANCELLED
      CANCELLED_WORDING, // 6 RUNNING+retry -> CANCELLED
    };
    for (int ordinal = 0; ordinal < ids.size(); ordinal++) {
      Entry toolResult = path.entries().get(resultEntryIndex(ordinal));
      assertEquals(expectedTexts[ordinal], resultTextOf(toolResult));
    }

    // 每个 sibling Tool 行已被 Stop 物理删除。
    for (UUID id : ids) {
      assertTrue(store.transaction(tx -> tx.findToolInvocation(id)).isEmpty());
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
    for (UUID id : ids) {
      assertFalse(
          store
              .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, id)))
              .isPresent());
    }

    // stopTools 在同一事务删除 parent Model 行（即使它是 active SUCCEEDED Tool phase），
    // 仅 ToolResult Entry 与 STOPPED TURN_END 保留。
    assertTrue(store.transaction(tx -> tx.findModelInvocation(baseline.modelId())).isEmpty());
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
            () -> runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1)));
    assertEquals(Reason.TERMINAL_APPLY_PENDING, error.reason());
    ToolInvocation tool =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.CANCELLED, tool.status());
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(1L, thread.version());
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

  /** invocation 追加的 ToolResult MESSAGE entry 中唯一的文本块。 */
  private String resultTextOf(Entry entry) {
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

  private static int resultEntryIndex(int ordinal) {
    return ordinal < 4 ? 4 + ordinal : 5 + ordinal;
  }
}
