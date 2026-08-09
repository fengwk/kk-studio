package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** EntryPath 链的不变量：同一 head 的 settings、turn 顺序、tool 前缀与结局。 */
class EntryPathTest {

  private static final long SESSION = 1L;
  private static final Instant BASE = Instant.ofEpochSecond(1000L);

  @Test
  void validPathWithOpenTurnDerivesHeadSettingsAndOpenTurnStart() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);

    EntryPath path = new EntryPath(List.of(root, start, user));

    assertEquals(root, path.root());
    assertEquals(user, path.head());
    assertEquals(settings("turn"), path.baseSettings());
    assertEquals(start, path.openTurnStart().orElseThrow());
    assertEquals(List.of(root, start, user), path.entries());
  }

  @Test
  void acceptsStructuredVideoUserMessageInInputTurn() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry videoUser =
        new Entry(
            3L,
            SESSION,
            2L,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(
                        new TextMessageContent("animate this"),
                        new VideoMessageContent(
                            "video/mp4", "https://example.test/reference.mp4"))),
                null,
                null),
            time(3L));

    EntryPath path = new EntryPath(List.of(root, start, videoUser));

    assertEquals(videoUser, path.head());
    assertEquals(start, path.openTurnStart().orElseThrow());
  }

  @Test
  void closedTurnThenSecondOpenTurnDerivesLatestSnapshot() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("first"));
    Entry user = userMessage(3L, 2L);
    Entry end = turnEnd(4L, 3L, 2L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);
    Entry secondStart = turnStart(5L, 4L, TurnStartReason.INPUT, settings("second"));
    Entry secondUser = userMessage(6L, 5L);

    EntryPath path = new EntryPath(List.of(root, start, user, end, secondStart, secondUser));

    assertEquals(settings("second"), path.baseSettings());
    assertEquals(secondStart, path.openTurnStart().orElseThrow());
  }

  @Test
  void baseSettingsReturnsExactLatestSnapshotWhenEnvironmentAndAgentChanged() {
    // 多个关闭 Turn 之间 environment/agent 都变化过，最新快照的 environment 为 null：
    // baseSettings() 必须返回最新 ROOT/TURN_START 的完整快照，绝不回看更旧的非 null environment/agent。
    BranchSettings rootSettings = settings(null, "root-agent");
    BranchSettings firstTurn =
        settings(new EnvironmentName("env-a"), "first-agent")
            .withModel(new ModelSelection("anthropic", "claude-sonnet", "custom"))
            .withActiveTools(List.of("read", "bash"));
    BranchSettings latestTurn = settings(null, "latest-agent");
    Entry root = root(rootSettings);
    Entry start1 = turnStart(2L, 1L, TurnStartReason.INPUT, firstTurn);
    Entry user1 = userMessage(3L, 2L);
    Entry end1 = turnEnd(4L, 3L, 2L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);
    Entry start2 = turnStart(5L, 4L, TurnStartReason.INPUT, latestTurn);
    Entry user2 = userMessage(6L, 5L);
    Entry end2 = turnEnd(7L, 6L, 5L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);

    EntryPath path = new EntryPath(List.of(root, start1, user1, end1, start2, user2, end2));

    BranchSettings base = path.baseSettings();
    assertEquals(latestTurn, base);
    assertNull(base.environmentName());
    assertEquals("latest-agent", base.agentName());
    assertEquals(new ModelSelection("anthropic", "claude-sonnet", "default"), base.model());
    assertEquals(List.of("read"), base.activeTools());
    assertNotEquals(firstTurn, base);
    assertNotEquals(rootSettings, base);
  }

  @Test
  void baseSettingsFallsBackToRootAndRelocationHead() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);

    EntryPath relocationPath = new EntryPath(List.of(root, start, user));
    EntryPath rootOnlyPath = new EntryPath(List.of(root));

    assertEquals(settings("turn"), relocationPath.baseSettings());
    assertEquals(settings("root"), rootOnlyPath.baseSettings());
    assertEquals(user, relocationPath.head());
    assertFalse(relocationPath.openTurnStart().isEmpty());
    assertTrue(rootOnlyPath.openTurnStart().isEmpty());
    assertEquals(root, rootOnlyPath.head());
  }

  @Test
  void relocationPathMayEndAtAnyHistoryHead() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry end = turnEnd(3L, 2L, 2L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);

    assertEquals(start, new EntryPath(List.of(root, start)).head());
    assertEquals(end, new EntryPath(List.of(root, start, end)).head());
    assertTrue(new EntryPath(List.of(root, start, end)).openTurnStart().isEmpty());
    assertEquals(settings("turn"), new EntryPath(List.of(root, start, end)).baseSettings());
  }

  @Test
  void sameHeadPathsDeriveIdenticalSettings() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);
    List<Entry> entries = List.of(root, start, user);

    EntryPath first = new EntryPath(entries);
    EntryPath second = new EntryPath(new ArrayList<>(entries));

    assertEquals(first.baseSettings(), second.baseSettings());
    assertEquals(first.head(), second.head());
  }

  @Test
  void entriesAreDefensivelyCopiedAndUnmodifiable() {
    Entry root = root(settings("root"));
    ArrayList<Entry> source = new ArrayList<>(List.of(root));
    EntryPath path = new EntryPath(source);
    source.clear();

    assertEquals(root, path.head());
    assertThrows(UnsupportedOperationException.class, () -> path.entries().add(root));
  }

  @Test
  void acceptsCompleteInputTurnWithOrderedToolLoop() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);
    Entry assistant = assistantMessage(4L, 3L, "call-1:read", "call-2:grep");
    Entry tool0 = toolResult(5L, 4L, 0, 4L, "call-1", "read");
    Entry tool1 = toolResult(6L, 5L, 1, 4L, "call-2", "grep");
    Entry end = turnEnd(7L, 6L, 2L, TurnEndOutcome.COMPLETED, null, null);

    EntryPath path = new EntryPath(List.of(root, start, user, assistant, tool0, tool1, end));

    assertEquals(end, path.head());
    assertTrue(path.openTurnStart().isEmpty());
  }

  @Test
  void acceptsCustomInputAndCompletedTurnWithoutToolCalls() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry custom = customMessage(3L, 2L);
    Entry assistant = assistantMessage(4L, 3L);
    Entry end = turnEnd(5L, 4L, 2L, TurnEndOutcome.COMPLETED, null, null);

    new EntryPath(List.of(root, start, custom, assistant, end));
  }

  @Test
  void acceptsContinuationTurnWithoutInput() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.CONTINUATION, settings("turn"));
    Entry assistant = assistantMessage(3L, 2L);
    Entry end = turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, null, null);

    new EntryPath(List.of(root, start, assistant, end));
  }

  @Test
  void acceptsCompletedCompactionTurnsForAllPhases() {
    Entry root = root(settings("root"));
    for (CompactionPhase phase :
        List.of(CompactionPhase.FULL, CompactionPhase.HISTORY, CompactionPhase.TURN_PREFIX)) {
      boolean complete = phase != CompactionPhase.HISTORY;
      Long prefix = phase == CompactionPhase.FULL ? null : 3L;
      new EntryPath(
          List.of(
              root,
              compactionStart(2L, 1L, settings("turn")),
              compactionResult(3L, 2L, phase, complete, prefix),
              turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, null, null)));
    }
  }

  @Test
  void completedCompactionContinueModelMatchesOverflowRecoverySemantics() {
    Entry root = root(settings("root"));
    Entry start = compactionStart(2L, 1L, settings("turn"));
    Entry threshold =
        compactionResult(3L, 2L, CompactionPhase.FULL, CompactionTrigger.THRESHOLD, true, null);
    Entry overflow =
        compactionResult(3L, 2L, CompactionPhase.FULL, CompactionTrigger.OVERFLOW, true, null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    threshold,
                    turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, true, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    overflow,
                    turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, false, null, null))));

    new EntryPath(
        List.of(
            root,
            start,
            overflow,
            turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, true, null, null)));
  }

  @Test
  void rejectsCompactionPayloadInNormalTurnsAndNormalResultsInCompactionTurns() {
    Entry root = root(settings("root"));
    // 普通 turn 绝不包含 COMPACTION payload。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn")),
                    userMessage(3L, 2L),
                    compactionResult(4L, 3L, CompactionPhase.FULL, true, null),
                    turnEnd(5L, 4L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    // COMPACTION turn 绝不消费 USER / CUSTOM / 普通 ASSISTANT 结果。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(2L, 1L, settings("turn")),
                    userMessage(3L, 2L),
                    compactionResult(4L, 3L, CompactionPhase.FULL, true, null),
                    turnEnd(5L, 4L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(2L, 1L, settings("turn")),
                    customMessage(3L, 2L),
                    compactionResult(4L, 3L, CompactionPhase.FULL, true, null),
                    turnEnd(5L, 4L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(2L, 1L, settings("turn")),
                    assistantMessage(3L, 2L),
                    turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    // COMPACTION turn 不能以普通 ASSISTANT 结果完成。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(2L, 1L, settings("turn")),
                    assistantMessage(3L, 2L),
                    turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, null, null))));
  }

  @Test
  void acceptsFailedAndStoppedCompactionBarriers() {
    Entry root = root(settings("root"));
    // 失败压缩：ASSISTANT_ERROR + FAILED。
    new EntryPath(
        List.of(
            root,
            compactionStart(2L, 1L, settings("turn")),
            assistantError(3L, 2L),
            turnEnd(4L, 3L, 2L, TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null)));
    // 停止压缩：ASSISTANT_ABORTED + STOPPED。
    new EntryPath(
        List.of(
            root,
            compactionStart(2L, 1L, settings("turn")),
            assistantAborted(3L, 2L),
            turnEnd(4L, 3L, 2L, TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, "stop-1")));
  }

  @Test
  void acceptsFailedStoppedAndCancelledOutcomes() {
    Entry root = root(settings("root"));

    new EntryPath(
        List.of(
            root,
            turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn")),
            userMessage(3L, 2L),
            assistantError(4L, 3L),
            turnEnd(5L, 4L, 2L, TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null)));

    new EntryPath(
        List.of(
            root,
            turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn")),
            userMessage(3L, 2L),
            assistantAborted(4L, 3L),
            turnEnd(5L, 4L, 2L, TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, "stop-1")));

    new EntryPath(
        List.of(
            root,
            turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn")),
            turnEnd(3L, 2L, 2L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null)));

    new EntryPath(
        List.of(
            root,
            turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn")),
            userMessage(3L, 2L),
            turnEnd(4L, 3L, 2L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null)));

    new EntryPath(
        List.of(
            root,
            turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn")),
            userMessage(3L, 2L),
            assistantMessage(4L, 3L, "call-1:read"),
            toolResult(5L, 4L, 0, 4L, "call-1", "read"),
            turnEnd(6L, 5L, 2L, TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, "stop-1")));
  }

  @Test
  void acceptsPartialToolPrefixesAtAnyHead() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);
    Entry assistant = assistantMessage(4L, 3L, "call-1:read", "call-2:grep");
    Entry tool0 = toolResult(5L, 4L, 0, 4L, "call-1", "read");

    new EntryPath(List.of(root, start));
    new EntryPath(List.of(root, start, user));
    new EntryPath(List.of(root, start, user, assistant));
    new EntryPath(List.of(root, start, user, assistant, tool0));
  }

  @Test
  void rejectsEntriesOutsideOpenTurn() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry end = turnEnd(3L, 2L, 2L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);

    assertThrows(
        IllegalArgumentException.class, () -> new EntryPath(List.of(root, userMessage(2L, 1L))));
    assertThrows(
        IllegalArgumentException.class, () -> new EntryPath(List.of(root, customMessage(2L, 1L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, end, userMessage(4L, 3L))));
  }

  @Test
  void rejectsInputPhaseViolations() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);

    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, toolResult(3L, 2L, 0, 99L, "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, assistantError(3L, 2L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, assistantAborted(3L, 2L))));
  }

  @Test
  void rejectsInputTurnWithoutInputBeforeAssistantResult() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, assistantMessage(3L, 2L))));
  }

  @Test
  void rejectsRepeatedOrLateAssistantResults() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);
    Entry assistant = assistantMessage(4L, 3L);

    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, assistantMessage(5L, 4L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, assistantAborted(5L, 4L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, assistantError(5L, 4L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, userMessage(5L, 4L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, customMessage(5L, 4L))));
  }

  @Test
  void rejectsMessagesInContinuationTurns() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.CONTINUATION, settings("turn"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, userMessage(3L, 2L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, customMessage(3L, 2L))));

    // Continuation 无需 input 即偿还上一条 TURN_END.continueModel 的义务。
    new EntryPath(
        List.of(
            root,
            start,
            assistantMessage(3L, 2L),
            turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, null, null)));
    new EntryPath(
        List.of(
            root,
            turnStart(2L, 1L, TurnStartReason.CONTINUATION, settings("turn")),
            assistantError(3L, 2L),
            turnEnd(4L, 3L, 2L, TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null)));
    new EntryPath(
        List.of(
            root,
            turnStart(2L, 1L, TurnStartReason.CONTINUATION, settings("turn")),
            assistantAborted(3L, 2L),
            turnEnd(4L, 3L, 2L, TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, "stop-1")));
  }

  @Test
  void rejectsToolResultsWithoutMatchingAssistantMessage() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L),
                    toolResult(5L, 4L, 0, 4L, "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantAborted(4L, 3L),
                    toolResult(5L, 4L, 0, 4L, "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantError(4L, 3L),
                    toolResult(5L, 4L, 0, 4L, "call-1", "read"))));
  }

  @Test
  void rejectsNonPrefixOrMismatchedToolResults() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read"),
                    toolResult(5L, 4L, 1, 4L, "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read", "call-2:grep"),
                    toolResult(5L, 4L, 1, 4L, "call-2", "grep"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read"),
                    toolResult(5L, 4L, 0, 4L, "call-9", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read"),
                    toolResult(5L, 4L, 0, 4L, "call-1", "grep"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read"),
                    toolResult(5L, 4L, 0, 99L, "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read"),
                    toolResult(5L, 4L, 0, 4L, "call-1", "read"),
                    toolResult(6L, 5L, 0, 4L, "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read"),
                    toolResult(5L, 4L, 0, 4L, "call-1", "read"),
                    toolResult(6L, 5L, 1, 4L, "call-1", "read"))));
  }

  @Test
  void rejectsTurnEndOutcomeViolations() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(3L, 2L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root, start, user, turnEnd(4L, 3L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantAborted(4L, 3L),
                    turnEnd(5L, 4L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantError(4L, 3L),
                    turnEnd(5L, 4L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read", "call-2:grep"),
                    toolResult(5L, 4L, 0, 4L, "call-1", "read"),
                    turnEnd(6L, 5L, 2L, TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    turnEnd(
                        4L, 3L, 2L, TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, "stop-1"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L, "call-1:read"),
                    turnEnd(
                        5L, 4L, 2L, TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, "stop-1"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantAborted(4L, 3L),
                    turnEnd(5L, 4L, 2L, TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(4L, 3L),
                    turnEnd(5L, 4L, 2L, TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    turnStart(2L, 1L, TurnStartReason.CONTINUATION, settings("turn")),
                    turnEnd(3L, 2L, 2L, TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null))));
  }

  @Test
  void rejectsEmptyPath() {
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of()));
  }

  @Test
  void rejectsMixedSessions() {
    Entry root = root(settings("root"));
    Entry other =
        new Entry(
            2L, 2L, 1L, new TurnStartPayload(TurnStartReason.INPUT, settings("turn")), time(2L));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, other)));
  }

  @Test
  void rejectsRootNotFirstAndMultipleRoots() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry secondRoot = new Entry(3L, SESSION, null, new RootPayload(settings("other")), time(3L));

    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(start, root)));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, secondRoot)));
  }

  @Test
  void rejectsBrokenParentChainAndDuplicateIds() {
    Entry root = root(settings("root"));
    Entry broken =
        new Entry(
            3L, SESSION, 5L, new TurnStartPayload(TurnStartReason.INPUT, settings("t")), time(3L));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, broken)));

    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry duplicate =
        new Entry(
            2L, SESSION, 2L, new TurnStartPayload(TurnStartReason.INPUT, settings("t")), time(3L));
    assertThrows(
        IllegalArgumentException.class, () -> new EntryPath(List.of(root, start, duplicate)));
  }

  @Test
  void rejectsCreatedAtBeforeParent() {
    Entry root = root(settings("root"));
    Entry child =
        new Entry(
            2L,
            SESSION,
            1L,
            new TurnStartPayload(TurnStartReason.INPUT, settings("turn")),
            BASE.minusSeconds(1));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, child)));
  }

  @Test
  void rejectsSecondOpenTurnStart() {
    Entry root = root(settings("root"));
    Entry first = turnStart(2L, 1L, TurnStartReason.INPUT, settings("first"));
    Entry second = turnStart(3L, 2L, TurnStartReason.INPUT, settings("second"));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, first, second)));
  }

  @Test
  void rejectsTurnEndWithoutOpenTurnStart() {
    Entry root = root(settings("root"));
    Entry user = userMessage(2L, 1L);
    Entry end = turnEnd(3L, 2L, 2L, TurnEndOutcome.COMPLETED, null, null);
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, user, end)));
  }

  @Test
  void rejectsTurnEndWithWrongTurnStartReference() {
    Entry root = root(settings("root"));
    Entry start = turnStart(2L, 1L, TurnStartReason.INPUT, settings("turn"));
    Entry end = turnEnd(3L, 2L, 99L, TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, start, end)));
  }

  @Test
  void customEntriesAreTransparentToTurnGrammar() {
    Entry root = root(settings("root"));
    Entry customBeforeTurn = customEntry(2L, 1L, "goal");
    Entry start = turnStart(3L, 2L, TurnStartReason.INPUT, settings("turn"));
    Entry customInsideTurn = customEntry(4L, 3L, "goal");
    Entry user = userMessage(5L, 4L);
    Entry assistant = assistantMessage(6L, 5L);
    Entry customAfterAssistant = customEntry(7L, 6L, "goal");
    Entry end = turnEnd(8L, 7L, 3L, TurnEndOutcome.COMPLETED, null, null);
    Entry customAfterTurn = customEntry(9L, 8L, "goal");

    EntryPath path =
        new EntryPath(
            List.of(
                root,
                customBeforeTurn,
                start,
                customInsideTurn,
                user,
                assistant,
                customAfterAssistant,
                end,
                customAfterTurn));

    assertEquals(
        4,
        path.entries().stream()
            .filter(entry -> entry.payload() instanceof CustomEntryPayload)
            .count());
    // CUSTOM 不打开/关闭 turn：closed turn 之后 openTurnStart 为空。
    assertEquals(Optional.empty(), path.openTurnStart());
    assertEquals(settings("turn"), path.baseSettings());
  }

  @Test
  void customEntryAfterRootWithoutAnyTurnIsAccepted() {
    Entry root = root(settings("root"));
    Entry custom = customEntry(2L, 1L, "goal");
    EntryPath path = new EntryPath(List.of(root, custom));
    assertEquals(custom, path.head());
    assertEquals(Optional.empty(), path.openTurnStart());
  }

  private static Entry root(BranchSettings settings) {
    return new Entry(1L, SESSION, null, new RootPayload(settings), BASE);
  }

  private static Entry turnStart(
      long id, long parentId, TurnStartReason reason, BranchSettings settings) {
    return new Entry(id, SESSION, parentId, new TurnStartPayload(reason, settings), time(id));
  }

  private static Entry turnEnd(
      long id,
      long parentId,
      long turnStartEntryId,
      TurnEndOutcome outcome,
      TurnEndReason reason,
      String closeRequestId) {
    return turnEnd(id, parentId, turnStartEntryId, outcome, false, reason, closeRequestId);
  }

  private static Entry turnEnd(
      long id,
      long parentId,
      long turnStartEntryId,
      TurnEndOutcome outcome,
      boolean continueModel,
      TurnEndReason reason,
      String closeRequestId) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new TurnEndPayload(turnStartEntryId, outcome, continueModel, reason, closeRequestId),
        time(id));
  }

  private static Entry userMessage(long id, long parentId) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi"))),
            null,
            null),
        time(id));
  }

  private static Entry customMessage(long id, long parentId) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new CustomMessagePayload(
            CustomMessagePayload.CORE_PLUGIN_ID,
            CustomMessagePayload.CORE_CUSTOM_TYPE,
            CustomMessagePayload.CORE_RENDERER_KEY,
            new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent("sys"))),
            CustomMessagePayload.CORE_DETAILS_JSON),
        time(id));
  }

  private static Entry customEntry(long id, long parentId, String customType) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new CustomEntryPayload("com.example.goal", customType, 1, "{\"s\":1}"),
        time(id));
  }

  private static Entry assistantMessage(long id, long parentId, String... toolCalls) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (String toolCall : toolCalls) {
      int separator = toolCall.indexOf(':');
      contents.add(
          new ToolCallMessageContent(
              toolCall.substring(0, separator),
              toolCall.substring(separator + 1),
              toolCall.substring(separator + 1),
              "{}"));
    }
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent("answer"));
    }
    ProviderStopReason stopReason =
        toolCalls.length == 0 ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS;
    return new Entry(
        id,
        SESSION,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, contents), metadata(stopReason), null),
        time(id));
  }

  private static Entry toolResult(
      long id,
      long parentId,
      int ordinal,
      long assistantEntryId,
      String toolCallId,
      String toolName) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.TOOL,
                List.of(
                    new ToolResultMessageContent(
                        toolCallId,
                        toolName,
                        toolName,
                        List.of(new TextMessageContent("ok")),
                        false,
                        "{}"))),
            null,
            new ToolResultMetadata(
                assistantEntryId, toolCallId, ordinal, ToolResultStatus.SUCCEEDED, false, null)),
        time(id));
  }

  private static Entry compactionStart(long id, long parentId, BranchSettings settings) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new TurnStartPayload(TurnStartReason.COMPACTION, settings),
        time(id));
  }

  private static Entry compactionResult(
      long id,
      long parentId,
      CompactionPhase phase,
      boolean complete,
      Long turnPrefixStartEntryId) {
    return compactionResult(
        id, parentId, phase, CompactionTrigger.THRESHOLD, complete, turnPrefixStartEntryId);
  }

  private static Entry compactionResult(
      long id,
      long parentId,
      CompactionPhase phase,
      CompactionTrigger trigger,
      boolean complete,
      Long turnPrefixStartEntryId) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new CompactionPayload(
            phase, trigger, 500L, complete, "summary", 2L, 4L, turnPrefixStartEntryId),
        time(id));
  }

  private static Entry assistantError(long id, long parentId) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new AssistantErrorPayload(new AssistantError("MODEL_FAILED", "down")),
        time(id));
  }

  private static Entry assistantAborted(long id, long parentId) {
    return new Entry(
        id,
        SESSION,
        parentId,
        new AssistantAbortedPayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("partial")))),
        time(id));
  }

  private static AssistantMessageMetadata metadata(ProviderStopReason reason) {
    ModelUsage usage = new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(2));
    return new AssistantMessageMetadata(reason, usage, cost);
  }

  private static Instant time(long id) {
    return BASE.plusSeconds(id);
  }

  private static BranchSettings settings(String agentName) {
    return settings(null, agentName);
  }

  private static BranchSettings settings(EnvironmentName environmentName, String agentName) {
    return new BranchSettings(
        environmentName,
        agentName,
        new ModelSelection("anthropic", "claude-sonnet", "default"),
        List.of("read"));
  }
}
