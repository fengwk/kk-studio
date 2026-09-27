package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskTerminalProjection.Terminal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 验证从子 Thread durable snapshot 推导终态、停止门禁与报告的纯函数契约。
 *
 * <p>测试意图：结算扫描的每一项判定都必须来自持久事实而非猜测——(1) 只有"静止 + 非 continuation 的 TURN_END + 边界之后有新条目"才算本次执行 终结；(2)
 * "静止 + 边界之后没有任何条目"只能来自"prompt 在被消费前被取消"或历史回退，必须被识别为不可能再产生终态；(3) prompt 仍 QUEUED 或子线程
 * 仍活跃时绝不能被误判为取消；(4) 只有非 continuation 的 STOPPED TURN_END 才是"父被显式停止"的门禁事实。
 */
class SubagentTaskTerminalProjectionTest {

  private static final UUID CHILD_THREAD_ID = new UUID(0L, 1L);
  private static final UUID BOUNDARY_ENTRY_ID = new UUID(0L, 2L);
  private static final UUID TURN_START_ENTRY_ID = new UUID(0L, 3L);
  private static final BranchSettings SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), null);

  @Test
  void completedExecutionProjectsLastAssistantReport() {
    // 测试意图：完成态报告取本次执行边界之后的最后一段助手文本，且不越界到边界之前的历史。
    ThreadSnapshot snapshot =
        snapshot(
            boundary(),
            turnStart(TurnStartReason.INPUT),
            assistant("first draft"),
            assistant("final answer"),
            turnEnd(TurnEndOutcome.COMPLETED));

    Terminal terminal = SubagentTaskTerminalProjection.terminal(snapshot, BOUNDARY_ENTRY_ID);

    assertEquals(Outcome.COMPLETED, terminal.outcome());
    assertEquals("final answer", terminal.report());
    assertNull(terminal.partialResult());
    assertNull(terminal.error());
  }

  @Test
  void failedExecutionKeepsErrorAndPartialReportSeparate() {
    // 测试意图：失败时 error 与部分结果分离投影，父既能知道失败原因也能拿到已有产出。
    ThreadSnapshot snapshot =
        snapshot(
            boundary(),
            turnStart(TurnStartReason.INPUT),
            assistant("partial text"),
            entry(
                new UUID(0L, 31L),
                new AssistantErrorPayload(new AssistantError("TURN_FAILED", "boom"), null)),
            turnEnd(TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED));

    Terminal terminal = SubagentTaskTerminalProjection.terminal(snapshot, BOUNDARY_ENTRY_ID);

    assertEquals(Outcome.ERROR, terminal.outcome());
    assertNull(terminal.report());
    assertEquals("boom", terminal.partialResult());
    assertTrue(terminal.error().contains("TURN_FAILED"), terminal.error());
  }

  @Test
  void stoppedExecutionKeepsLastSafeAssistantText() {
    // 测试意图：被停止的执行仍要把它已产出的安全内容作为 partial 交给父，而不是只报"被取消"。
    ThreadSnapshot snapshot =
        snapshot(
            boundary(),
            turnStart(TurnStartReason.INPUT),
            entry(new UUID(0L, 32L), new AssistantAbortedPayload(assistantMessage("half done"))),
            turnEnd(TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP));

    Terminal terminal = SubagentTaskTerminalProjection.terminal(snapshot, BOUNDARY_ENTRY_ID);

    assertEquals(Outcome.CANCELLED, terminal.outcome());
    assertEquals("half done", terminal.partialResult());
  }

  @Test
  void runningExecutionHasNoTerminal() {
    // 测试意图：模型活跃、工具活跃、仍有排队命令、head 是 continuation 时一律不得判定为终结。
    assertNull(
        SubagentTaskTerminalProjection.terminal(
            snapshot(List.of(boundary()), mock(ModelInvocation.class), List.of()),
            BOUNDARY_ENTRY_ID));
    assertNull(SubagentTaskTerminalProjection.terminal(snapshot(boundary()), BOUNDARY_ENTRY_ID));
    assertNull(
        SubagentTaskTerminalProjection.terminal(
            snapshot(boundary(), turnStart(TurnStartReason.INPUT)), BOUNDARY_ENTRY_ID));
    assertNull(
        SubagentTaskTerminalProjection.terminal(
            snapshot(boundary(), turnEnd(TurnEndOutcome.COMPLETED, null, true)),
            BOUNDARY_ENTRY_ID));
    assertNull(
        SubagentTaskTerminalProjection.terminal(
            snapshot(
                List.of(boundary(), turnEnd(TurnEndOutcome.COMPLETED)),
                null,
                List.of(mock(ThreadCommand.class))),
            BOUNDARY_ENTRY_ID));
  }

  @Test
  void abortedBeforeStartRequiresQuiescenceAndNoNewEntries() {
    // 测试意图：边界之后没有任何条目且子线程静止时才判定"不可能再产生终态"；已有新条目或仍活跃时不得误判。
    assertTrue(
        SubagentTaskTerminalProjection.abortedBeforeStart(snapshot(boundary()), BOUNDARY_ENTRY_ID));
    // 边界已不在路径中（历史被回退）：同样不可能再产生本次执行的终态。
    assertTrue(
        SubagentTaskTerminalProjection.abortedBeforeStart(
            snapshot(turnStart(TurnStartReason.INPUT)), BOUNDARY_ENTRY_ID));

    assertFalse(
        SubagentTaskTerminalProjection.abortedBeforeStart(
            snapshot(
                List.of(boundary(), turnEnd(TurnEndOutcome.COMPLETED)),
                null,
                List.of(mock(ThreadCommand.class))),
            BOUNDARY_ENTRY_ID));
    assertFalse(
        SubagentTaskTerminalProjection.abortedBeforeStart(
            snapshot(boundary(), turnStart(TurnStartReason.INPUT)), BOUNDARY_ENTRY_ID));
    assertFalse(
        SubagentTaskTerminalProjection.abortedBeforeStart(
            snapshot(List.of(boundary()), mock(ModelInvocation.class), List.of()),
            BOUNDARY_ENTRY_ID));
  }

  @Test
  void stoppedBoundaryIsDetectedAsExplicitStop() {
    // 测试意图：只有非 continuation 的 STOPPED TURN_END 才是"父被显式停止"的门禁事实；完成态与 continuation 都不是。
    assertTrue(
        SubagentTaskTerminalProjection.stoppedHead(
            turnEnd(TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP)));
    assertFalse(SubagentTaskTerminalProjection.stoppedHead(turnEnd(TurnEndOutcome.COMPLETED)));
    assertFalse(
        SubagentTaskTerminalProjection.stoppedHead(turnEnd(TurnEndOutcome.COMPLETED, null, true)));
  }

  @Test
  void turnsAreCountedAfterBoundaryExcludingCompactionAndStop() {
    // 测试意图：max_turns 软预算只统计本次执行边界之后的**模型工作** turn，压缩 turn 与显式 STOP 停止屏障都不计入。
    ThreadSnapshot snapshot =
        snapshot(
            boundary(), turnStart(TurnStartReason.INPUT), turnStart(TurnStartReason.CONTINUATION));

    assertEquals(2, SubagentTaskTerminalProjection.countTurns(snapshot, BOUNDARY_ENTRY_ID));

    // STOP barrier Turn（TURN_START(STOP) → 取消屏障 → STOPPED TURN_END）不是模型工作轮。
    ThreadSnapshot withStop =
        snapshot(
            boundary(),
            turnStart(TurnStartReason.INPUT),
            turnStart(TurnStartReason.CONTINUATION),
            turnStart(TurnStartReason.STOP),
            assistantError(),
            turnEnd(TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP));
    assertEquals(2, SubagentTaskTerminalProjection.countTurns(withStop, BOUNDARY_ENTRY_ID));
    // 边界之前的 turn 不属于本次执行。
    assertEquals(
        0,
        SubagentTaskTerminalProjection.countTurns(
            snapshot, snapshot.entryPath().entries().getLast().id()));
  }

  private static Entry boundary() {
    return entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS));
  }

  private static Entry turnStart(TurnStartReason reason) {
    return entry(UUID.randomUUID(), new TurnStartPayload(reason, SETTINGS, CHILD_THREAD_ID));
  }

  private static Entry assistant(String text) {
    return entry(
        UUID.randomUUID(), new MessagePayload(assistantMessage(text), assistantMetadata(), null));
  }

  private static AgentMessage assistantMessage(String text) {
    return new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return new AssistantMessageMetadata(
        GenerationStopReason.COMPLETE,
        new ModelUsage(0, 0, 0, 0, 0, 0, 0),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static Entry assistantError() {
    return entry(
        UUID.randomUUID(),
        new AssistantErrorPayload(new AssistantError("CANCELLED", "Cancelled by user"), null));
  }

  private static Entry turnEnd(TurnEndOutcome outcome) {
    return turnEnd(outcome, null);
  }

  private static Entry turnEnd(TurnEndOutcome outcome, TurnEndReason reason) {
    return turnEnd(outcome, reason, false);
  }

  private static Entry turnEnd(
      TurnEndOutcome outcome, TurnEndReason reason, boolean continueModel) {
    return entry(
        UUID.randomUUID(),
        new TurnEndPayload(
            TURN_START_ENTRY_ID,
            outcome,
            continueModel,
            reason,
            outcome == TurnEndOutcome.STOPPED ? UUID.randomUUID() : null));
  }

  private static Entry entry(EntryPayload payload) {
    return entry(UUID.randomUUID(), payload);
  }

  private static Entry entry(UUID id, EntryPayload payload) {
    Entry entry = mock(Entry.class);
    when(entry.id()).thenReturn(id);
    when(entry.payload()).thenReturn(payload);
    return entry;
  }

  private static ThreadSnapshot snapshot(Entry... entries) {
    return snapshot(List.of(entries), null, List.of());
  }

  private static ThreadSnapshot snapshot(Entry entry, ModelInvocation model) {
    return snapshot(List.of(entry), model, List.of());
  }

  private static ThreadSnapshot snapshot(List<Entry> entries) {
    return snapshot(entries, null, List.of());
  }

  private static ThreadSnapshot snapshot(List<Entry> entries, ModelInvocation model) {
    return snapshot(entries, model, List.of());
  }

  private static ThreadSnapshot snapshot(
      List<Entry> entries, ModelInvocation model, List<ThreadCommand> queued) {
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    EntryPath path = mock(EntryPath.class);
    ThreadState thread = mock(ThreadState.class);
    when(snapshot.entryPath()).thenReturn(path);
    when(path.entries()).thenReturn(entries);
    Entry head = entries.isEmpty() ? boundary() : entries.getLast();
    when(path.head()).thenReturn(head);
    when(snapshot.model()).thenReturn(model);
    when(snapshot.queuedCommands()).thenReturn(queued);
    when(snapshot.toolSiblings()).thenReturn(List.of());
    when(snapshot.thread()).thenReturn(thread);
    when(thread.id()).thenReturn(CHILD_THREAD_ID);
    return snapshot;
  }
}
