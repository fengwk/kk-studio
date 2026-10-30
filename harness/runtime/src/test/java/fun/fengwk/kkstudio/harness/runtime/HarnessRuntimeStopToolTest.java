package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.approveTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.retryReadyTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelFreeToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.succeedTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.targetReceipt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
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
 * Stop 在 TOOL_ACTIVE 上：每个 sibling 按自身状态收敛（WAITING_APPROVAL/READY 转 CANCELLED， RUNNING 转
 * UNKNOWN，terminal 保持原样），按 callIndex 的 ToolResult Entry 以 invocation-backed 方式挂载且
 * synthetic=false，删除 THREAD 与全部 TOOL Work 行； TOOL_TERMINAL_PENDING 保持零变更。
 *
 * <p>这些工具批次都没有 Model 父行：工具路径的归属完全由 Thread/TurnStart/Assistant 决定。
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
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void statusMatrixConvergesEverySiblingByItsOwnStatus() {
    HarnessRuntimeTestSupport.ModelFreeMultiToolBaseline baseline =
        seedModelFreeToolBaseline(store, 7);
    List<UUID> ids = baseline.toolIds();
    // Stop 前捕获每个 sibling 的 durable call id：ToolResult Entry 必须以 invocation-backed 方式挂载同一个 call。
    List<String> toolCallIds = ids.stream().map(id -> toolCallId(store, id)).toList();
    inTransaction(
        store,
        tx -> {
          ToolInvocation waiting = tx.tools().lock(ids.get(0)).orElseThrow();
          tx.tools().update(List.of(waiting.requestApproval("approval needed", T5)));
        });
    approveTool(store, ids.get(2));
    markRunningTool(store, ids.get(2));
    approveTool(store, ids.get(3));
    markRunningTool(store, ids.get(3));
    approveTool(store, ids.get(4));
    markRunningTool(store, ids.get(4));
    ToolEffectBatch effects =
        new ToolEffectBatch(
            List.of(new CustomEntryPayload("goal", "state", 1, "{\"status\":\"active\"}")));
    succeedTool(store, ids.get(4), effects);
    cancelTool(store, ids.get(5));
    approveTool(store, ids.get(6));
    markRunningTool(store, ids.get(6));
    retryReadyTool(store, ids.get(6));
    seedThreadWork(store, baseline.threadId());
    for (UUID id : ids) {
      seedToolWork(store, id);
    }

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));
    assertStopped(result);
    assertEquals(0, targetReceipt(result).cancelledCommandCount());
    assertEquals(2L, result.thread().version());

    ThreadState stored =
        store.transaction(tx -> tx.threads().lock(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.entries().loadPath(stored.headEntryId()));
    assertEquals(13, path.entries().size());
    assertEquals(
        new CustomEntryPayload("goal", "state", 1, "{\"status\":\"active\"}"),
        path.entries().get(8).payload());
    for (int callIndex = 0; callIndex < ids.size(); callIndex++) {
      Entry entry = path.entries().get(resultEntryIndex(callIndex));
      assertTrue(entry.payload() instanceof MessagePayload);
      ToolResultMetadata metadata = ((MessagePayload) entry.payload()).toolResultMetadata();
      assertEquals(baseline.assistantEntryId(), metadata.assistantEntryId());
      assertEquals(toolCallIds.get(callIndex), metadata.toolCallId());
      assertEquals(callIndex, metadata.callIndex());
      assertFalse(metadata.synthetic());
      assertEquals(
          callIndex == 4 ? ToolResultStatus.SUCCEEDED : metadataStatus(callIndex),
          metadata.status());
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
    // RUNNING → UNKNOWN，SUCCEEDED → SUCCEEDED）；attempt 不再 durable（行被物理删除），
    // 只能通过 Entry metadata.status + 内容文本验证状态收敛。
    assertFalse(CANCELLED_WORDING.contains("never"));
    String[] expectedTexts = {
      CANCELLED_WORDING, // 0 WAITING_APPROVAL -> CANCELLED
      CANCELLED_WORDING, // 1 READY -> CANCELLED
      UNKNOWN_WORDING, // 2 RUNNING -> UNKNOWN
      UNKNOWN_WORDING, // 3 RUNNING -> UNKNOWN
      "real result", // 4 SUCCEEDED -> SUCCEEDED
      "cancelled", // 5 CANCELLED -> CANCELLED
      CANCELLED_WORDING, // 6 RUNNING+retry -> CANCELLED
    };
    for (int callIndex = 0; callIndex < ids.size(); callIndex++) {
      Entry toolResult = path.entries().get(resultEntryIndex(callIndex));
      assertEquals(expectedTexts[callIndex], resultTextOf(toolResult));
    }

    // 每个 sibling Tool 行已被 Stop 物理删除。
    for (UUID id : ids) {
      assertTrue(store.transaction(tx -> tx.tools().find(id)).isEmpty());
    }

    // 全部 Work 删除：THREAD 与每个 sibling TOOL（没有 Model 父行，也就没有 MODEL Work）。
    assertFalse(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
    for (UUID id : ids) {
      assertFalse(
          store
              .transaction(tx -> tx.work().find(new WorkTarget(WorkTargetType.TOOL, id)))
              .isPresent());
    }
  }

  @Test
  void approvalAndStopDoNotRequireModelInvocation() {
    // 合法 Assistant 工具批次没有 Model 父行：审批与 Stop 都以 Thread/TurnStart/Assistant 归属完成。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedModelFreeToolBaseline(store);
    assertNull(baseline.modelId());
    setWaitingApproval(store, baseline);

    ToolInvocation approved =
        runtime.decideToolApproval(
            new ToolApprovalCommand(
                baseline.threadId(),
                baseline.toolId(),
                ToolApprovalDecision.ALLOWED,
                TestIds.id(1),
                "alice",
                null));
    assertEquals(ToolInvocationStatus.READY, approved.status());
    assertEquals(ToolApprovalDecision.ALLOWED, approved.approval().decision());
    assertFalse(approved.approval().isUndecided());

    StopResult result =
        runtime.stop(
            new StopCommand(
                baseline.threadId(),
                TestIds.id(2),
                store
                    .transaction(tx -> tx.threads().lock(baseline.threadId()).orElseThrow())
                    .version()));
    assertStopped(result);
    ThreadState stored =
        store.transaction(tx -> tx.threads().lock(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.entries().loadPath(stored.headEntryId()));
    Entry toolResult =
        path.entries().stream()
            .filter(
                entry ->
                    entry.payload() instanceof MessagePayload message
                        && message.toolResultMetadata() != null)
            .findFirst()
            .orElseThrow();
    ToolResultMetadata metadata = ((MessagePayload) toolResult.payload()).toolResultMetadata();
    assertEquals(ToolResultStatus.CANCELLED, metadata.status());
    assertEquals(baseline.assistantEntryId(), metadata.assistantEntryId());
    assertEquals("call-1", metadata.toolCallId());
    assertEquals(0, metadata.callIndex());
    assertFalse(metadata.synthetic());
    TurnEndPayload turnEnd = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.STOPPED, turnEnd.outcome());
    assertEquals(TurnEndReason.USER_STOP, turnEnd.reason());
    assertEquals(baseline.turnStartEntryId(), turnEnd.turnStartEntryId());
    assertTrue(store.transaction(tx -> tx.tools().find(baseline.toolId())).isEmpty());
  }

  @Test
  void terminalPendingToolStopMaterializesToolResultsThenStops() {
    // 模型自由的工具批次（无 Model 父行），唯一 Tool sibling 已 terminal 但结果尚未物化。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedModelFreeToolBaseline(store);
    cancelTool(store, baseline);
    seedThreadWork(store, baseline.threadId());
    seedToolWork(store, baseline.toolId());

    // 提交先于 Stop：Stop 不 409，而是把已 terminal 的 tool sibling 结果物化进历史后正常停止。
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));
    assertStopped(result);
    ThreadState stored =
        store.transaction(tx -> tx.threads().lock(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.entries().loadPath(stored.headEntryId()));
    // ToolResult 已物化（TOOL 角色），并保留 batch 关闭的 COMPLETED TURN_END；head 为 STOP 边界。
    assertTrue(
        path.entries().stream()
            .anyMatch(
                e ->
                    e.payload() instanceof MessagePayload msg && msg.toolResultMetadata() != null));
    assertTrue(
        path.entries().stream()
            .anyMatch(
                e ->
                    e.payload() instanceof TurnEndPayload end
                        && end.outcome() == TurnEndOutcome.COMPLETED));
    assertEquals(TurnEndOutcome.STOPPED, ((TurnEndPayload) path.head().payload()).outcome());
    // 全部 Invocation 行与 Work 删除（没有 Model 父行，也就没有 MODEL 行与 MODEL Work）。
    assertTrue(store.transaction(tx -> tx.tools().find(baseline.toolId())).isEmpty());
    assertFalse(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
    assertFalse(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())))
            .isPresent());
  }

  /** Stop 前读取 ToolInvocation 的 durable call id：ToolResult Entry 必须挂载同一个 call。 */
  private static String toolCallId(InMemoryHarnessStore store, UUID toolId) {
    return store.transaction(tx -> tx.tools().find(toolId).orElseThrow()).call().id();
  }

  /** invocation 追加的 ToolResult MESSAGE entry 中唯一的文本块。 */
  private String resultTextOf(Entry entry) {
    MessagePayload payload = (MessagePayload) entry.payload();
    ToolResultMessageContent content =
        (ToolResultMessageContent) payload.message().contents().get(0);
    AgentMessageContent text = content.contents().get(0);
    return ((TextMessageContent) text).text();
  }

  private static ToolResultStatus metadataStatus(int callIndex) {
    return switch (callIndex) {
      case 0, 1, 5, 6 -> ToolResultStatus.CANCELLED;
      case 2, 3 -> ToolResultStatus.UNKNOWN;
      default -> throw new IllegalArgumentException("unexpected callIndex " + callIndex);
    };
  }

  private static int resultEntryIndex(int callIndex) {
    return callIndex < 4 ? 4 + callIndex : 5 + callIndex;
  }
}
