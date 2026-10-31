package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 当前状态只保留分类锚点：控制 Entry、open/closed Turn、settings 与精确计数各自独立。 */
class EntryCurrentStateTest {

  private static final UUID SESSION = id(1L);
  private static final UUID OWNER = id(9L);
  private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");

  @Test
  void trailingControlEntriesDoNotHideClosedTurnOrAppliedSettings() {
    // 意图：尾部 Notification/Settings/Fork 不改变已关闭回合，settings 取最近 SETTINGS。
    Entry root = root(settings("root"));
    Entry start = turn(id(2L), root.id(), TurnStartReason.INPUT, settings("turn"));
    Entry user = message(id(3L), start.id(), AgentMessageRole.USER, false);
    Entry end = turnEnd(id(4L), user.id(), start.id());
    Entry settings = settings(id(5L), end.id(), settings("applied"));
    Entry notice = notification(id(6L), settings.id());
    Entry fork = fork(id(7L), notice.id(), root.id());

    EntryCurrentState state =
        EntryCurrentState.from(
            new EntryPath(List.of(root, start, user, end, settings, notice, fork)));

    assertEquals(fork, state.head());
    assertEquals(end, state.headIgnoringTrailingControlEntries());
    assertTrue(state.openTurnStart().isEmpty());
    assertEquals(start, state.referencedTurnStartEntry());
    assertEquals(settings("applied"), state.baseSettings());
  }

  @Test
  void openTurnExposesItsStartUntilTurnEnd() {
    // 意图：未关闭回合暴露 TURN_START，且没有 referenced start。
    Entry root = root(settings("root"));
    Entry start = turn(id(2L), root.id(), TurnStartReason.INPUT, settings("turn"));
    Entry user = message(id(3L), start.id(), AgentMessageRole.USER, false);

    EntryCurrentState state = EntryCurrentState.from(new EntryPath(List.of(root, start, user)));

    assertEquals(user, state.head());
    assertEquals(start, state.openTurnStart().orElseThrow());
    assertNull(state.referencedTurnStartEntry());
    assertEquals(settings("turn"), state.baseSettings());
  }

  @Test
  void closedTurnReferencesItsOwnStartAndSkipsCompactionSettings() {
    // 意图：TURN_END 暴露被引用且在祖先链上的 TURN_START；COMPACTION settings 不覆盖分支模型。
    Entry root = root(settings("root"));
    Entry start = turn(id(2L), root.id(), TurnStartReason.INPUT, settings("live"));
    Entry user = message(id(3L), start.id(), AgentMessageRole.USER, false);
    Entry assistant = message(id(4L), user.id(), AgentMessageRole.ASSISTANT, true);
    Entry end = turnEnd(id(5L), assistant.id(), start.id());
    Entry compaction =
        compaction(id(6L), end.id(), settings("poison").withModel(ModelSelection.model("s", "v2")));
    Entry summary = compactionResult(id(7L), compaction.id());
    Entry compactionEnd = turnEnd(id(8L), summary.id(), compaction.id());
    Entry control = notification(id(9L), compactionEnd.id());

    EntryCurrentState openCompaction =
        EntryCurrentState.from(
            new EntryPath(List.of(root, start, user, assistant, end, compaction)));
    assertEquals(compaction, openCompaction.headIgnoringTrailingControlEntries());
    assertEquals(compaction, openCompaction.openTurnStart().orElseThrow());
    assertNull(openCompaction.referencedTurnStartEntry());
    assertEquals(settings("live"), openCompaction.baseSettings());

    EntryCurrentState state =
        EntryCurrentState.from(
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistant,
                    end,
                    compaction,
                    summary,
                    compactionEnd,
                    control)));

    assertEquals(compactionEnd, state.headIgnoringTrailingControlEntries());
    assertTrue(state.openTurnStart().isEmpty());
    assertEquals(compaction, state.referencedTurnStartEntry());
    assertEquals(settings("live"), state.baseSettings());
  }

  @Test
  void rootOnlyHasNoOpenOrReferencedTurn() {
    Entry root = root(settings("root"));

    EntryCurrentState state = EntryCurrentState.from(new EntryPath(List.of(root)));

    assertEquals(root, state.head());
    assertEquals(root, state.headIgnoringTrailingControlEntries());
    assertFalse(state.openTurnStart().isPresent());
    assertNull(state.referencedTurnStartEntry());
    assertEquals(settings("root"), state.baseSettings());
  }

  @Test
  void metricsCountInputAndContinuationButOnlyAssistantToolCalls() {
    // 意图：COMPACTION/STOP 不计轮次；USER/TOOL 消息里的 ToolCall 内容不计工具调用。
    Entry root = root(settings("root"));
    Entry input = turn(id(2L), root.id(), TurnStartReason.INPUT, settings("live"));
    Entry user = message(id(3L), input.id(), AgentMessageRole.USER, false);
    Entry assistant = message(id(4L), user.id(), AgentMessageRole.ASSISTANT, true);
    Entry end = turnEnd(id(5L), assistant.id(), input.id());
    Entry continuation = turn(id(6L), end.id(), TurnStartReason.CONTINUATION, settings("live"));
    Entry continuationEnd = turnEnd(id(7L), continuation.id(), continuation.id());
    Entry compaction = compaction(id(8L), continuationEnd.id(), settings("poison"));
    Entry summary = compactionResult(id(9L), compaction.id());
    Entry compactionEnd = turnEnd(id(10L), summary.id(), compaction.id());
    Entry stop = turn(id(11L), compactionEnd.id(), TurnStartReason.STOP, settings("live"));

    EntryPathMetrics metrics =
        EntryPathMetrics.from(
            List.of(
                root,
                input,
                user,
                assistant,
                end,
                continuation,
                continuationEnd,
                compaction,
                summary,
                compactionEnd,
                stop));

    assertEquals(2, metrics.turnCount());
    assertEquals(1, metrics.toolCallCount());
    assertEquals(
        new EntryPathMetrics(0, 0), EntryPathMetrics.from(new EntryPath(List.of(root)).entries()));
    assertThrows(NullPointerException.class, () -> EntryPathMetrics.from(null));
    assertThrows(IllegalArgumentException.class, () -> new EntryPathMetrics(-1, 0));
  }

  private static Entry root(BranchSettings settings) {
    return new Entry(id(1L), SESSION, null, new RootPayload(settings), BASE);
  }

  private static Entry turn(
      UUID entryId, UUID parentId, TurnStartReason reason, BranchSettings settings) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        new TurnStartPayload(reason, settings, OWNER),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static Entry compactionResult(UUID entryId, UUID parentId) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        new CompactionPayload("summary", null),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static Entry compaction(UUID entryId, UUID parentId, BranchSettings settings) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        new TurnStartPayload(
            TurnStartReason.COMPACTION,
            settings,
            OWNER,
            null,
            null,
            CompactionStart.pending(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, id(1L), null, null),
            null),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static Entry turnEnd(UUID entryId, UUID parentId, UUID turnStartId) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        new TurnEndPayload(
            turnStartId, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static Entry message(
      UUID entryId, UUID parentId, AgentMessageRole role, boolean toolCall) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        new MessagePayload(
            new AgentMessage(
                role,
                List.of(
                    toolCall
                        ? new ToolCallMessageContent("call", "bash", "bash", "{}")
                        : new TextMessageContent("text"))),
            role == AgentMessageRole.ASSISTANT
                ? new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage())
                : null,
            null),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static Entry settings(UUID entryId, UUID parentId, BranchSettings settings) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        new SettingsPayload(settings, OWNER),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static Entry notification(UUID entryId, UUID parentId) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        new NotificationPayload(
            id(80L),
            NotificationKind.SUBAGENT_RESULT,
            OWNER,
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("notice")))),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static Entry fork(UUID entryId, UUID parentId, UUID sourceEntryId) {
    return new Entry(
        entryId,
        SESSION,
        parentId,
        ForkPayload.branch(sourceEntryId),
        BASE.plusSeconds(entryId.getLeastSignificantBits()));
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 1L, 0L, 0L, Map.of(), 0L, 2L);
  }

  private static BranchSettings settings(String agent) {
    return new BranchSettings(agent, ModelSelection.model("claude", "default"), null);
  }
}
