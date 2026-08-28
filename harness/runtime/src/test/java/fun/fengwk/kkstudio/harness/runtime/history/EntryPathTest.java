package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** EntryPath 链的不变量：同一 head 的 settings、turn 顺序、tool 前缀与结局。 */
class EntryPathTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final Instant BASE = Instant.ofEpochSecond(1000L);

  @Test
  void validPathWithOpenTurnDerivesHeadSettingsAndOpenTurnStart() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));

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
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry videoUser =
        new Entry(
            id(3L),
            SESSION_ID,
            id(2L),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(
                        new TextMessageContent("animate this"),
                        new VideoMessageContent(
                            "video/mp4", "https://example.test/reference.mp4"))),
                null,
                null),
            time(id(3L)));

    EntryPath path = new EntryPath(List.of(root, start, videoUser));

    assertEquals(videoUser, path.head());
    assertEquals(start, path.openTurnStart().orElseThrow());
  }

  @Test
  void closedTurnThenSecondOpenTurnDerivesLatestSnapshot() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("first"));
    Entry user = userMessage(id(3L), id(2L));
    Entry end =
        turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);
    Entry secondStart = turnStart(id(5L), id(4L), TurnStartReason.INPUT, settings("second"));
    Entry secondUser = userMessage(id(6L), id(5L));

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
        settings(EnvironmentBindings.binding("env-a"), "first-agent")
            .withModel(new ModelSelection("anthropic", "claude-sonnet", "custom"));
    BranchSettings latestTurn = settings(null, "latest-agent");
    Entry root = root(rootSettings);
    Entry start1 = turnStart(id(2L), id(1L), TurnStartReason.INPUT, firstTurn);
    Entry user1 = userMessage(id(3L), id(2L));
    Entry end1 =
        turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);
    Entry start2 = turnStart(id(5L), id(4L), TurnStartReason.INPUT, latestTurn);
    Entry user2 = userMessage(id(6L), id(5L));
    Entry end2 =
        turnEnd(id(7L), id(6L), id(5L), TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);

    EntryPath path = new EntryPath(List.of(root, start1, user1, end1, start2, user2, end2));

    BranchSettings base = path.baseSettings();
    assertEquals(latestTurn, base);
    assertNull(base.environment());
    assertEquals("latest-agent", base.agentName());
    assertEquals(new ModelSelection("anthropic", "claude-sonnet", "default"), base.model());
    assertNotEquals(firstTurn, base);
    assertNotEquals(rootSettings, base);
  }

  @Test
  void compactionTurnSettingsNeverChangeBranchProjection() {
    BranchSettings live = settings("live");
    BranchSettings compactionOnly =
        settings("poison").withModel(new ModelSelection("fallback", "summary", "v2"));
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, live);
    Entry user = userMessage(id(3L), id(2L));
    Entry assistant = assistantMessage(id(4L), id(3L));
    Entry end = turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, null, null);
    Entry compaction = compactionStart(id(6L), id(5L), compactionOnly);

    EntryPath path = new EntryPath(List.of(root, start, user, assistant, end, compaction));

    assertEquals(live, path.baseSettings());
    assertNotEquals(compactionOnly, path.baseSettings());
  }

  @Test
  void baseSettingsFallsBackToRootAndRelocationHead() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));

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
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry end =
        turnEnd(id(3L), id(2L), id(2L), TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);

    assertEquals(start, new EntryPath(List.of(root, start)).head());
    assertEquals(end, new EntryPath(List.of(root, start, end)).head());
    assertTrue(new EntryPath(List.of(root, start, end)).openTurnStart().isEmpty());
    assertEquals(settings("turn"), new EntryPath(List.of(root, start, end)).baseSettings());
  }

  @Test
  void sameHeadPathsDeriveIdenticalSettings() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));
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
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));
    Entry assistant = assistantMessage(id(4L), id(3L), "call-1:read", "call-2:grep");
    Entry tool0 = toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read");
    Entry tool1 = toolResult(id(6L), id(5L), 1, id(4L), "call-2", "grep");
    Entry end = turnEnd(id(7L), id(6L), id(2L), TurnEndOutcome.COMPLETED, null, null);

    EntryPath path = new EntryPath(List.of(root, start, user, assistant, tool0, tool1, end));

    assertEquals(end, path.head());
    assertTrue(path.openTurnStart().isEmpty());
  }

  @Test
  void acceptsCustomInputAndCompletedTurnWithoutToolCalls() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry custom = customMessage(id(3L), id(2L));
    Entry assistant = assistantMessage(id(4L), id(3L));
    Entry end = turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, null, null);

    new EntryPath(List.of(root, start, custom, assistant, end));
  }

  @Test
  void acceptsContinuationTurnWithoutInput() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.CONTINUATION, settings("turn"));
    Entry assistant = assistantMessage(id(3L), id(2L));
    Entry end = turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, null, null);

    new EntryPath(List.of(root, start, assistant, end));
  }

  @Test
  void acceptsCompletedCompactionTurnsForAllPhases() {
    Entry root = root(settings("root"));
    for (CompactionPhase phase :
        List.of(CompactionPhase.FULL, CompactionPhase.HISTORY, CompactionPhase.TURN_PREFIX)) {
      UUID prefix = phase == CompactionPhase.FULL ? null : id(3L);
      new EntryPath(
          List.of(
              root,
              compactionStart(
                  id(2L),
                  id(1L),
                  settings("turn"),
                  phase,
                  CompactionTrigger.THRESHOLD,
                  prefix,
                  null),
              compactionResult(id(3L), id(2L)),
              turnEnd(
                  id(4L),
                  id(3L),
                  id(2L),
                  TurnEndOutcome.COMPLETED,
                  phase == CompactionPhase.HISTORY,
                  null,
                  null)));
    }
  }

  @Test
  void completedCompactionContinueModelMatchesRecoverySemantics() {
    Entry root = root(settings("root"));
    Entry thresholdStart =
        compactionStart(
            id(2L),
            id(1L),
            settings("turn"),
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            null,
            null);
    Entry overflowStart =
        compactionStart(
            id(2L),
            id(1L),
            settings("turn"),
            CompactionPhase.FULL,
            CompactionTrigger.OVERFLOW,
            null,
            null);
    Entry result = compactionResult(id(3L), id(2L));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    thresholdStart,
                    result,
                    turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, true, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    overflowStart,
                    result,
                    turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, false, null, null))));

    new EntryPath(
        List.of(
            root,
            overflowStart,
            result,
            turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, true, null, null)));

    // active normal continuation 可以跨 THRESHOLD checkpoint 保留；没有此前 obligation 的同形仍由上方逆证拒绝。
    Entry inputStart = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));
    Entry assistant = assistantMessage(id(4L), id(3L));
    Entry inputEnd = turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, true, null, null);
    Entry thresholdAfterContinuation =
        compactionStart(
            id(6L),
            id(5L),
            settings("turn"),
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            null,
            null);
    Entry thresholdResult = compactionResult(id(7L), id(6L));
    Entry thresholdEnd =
        turnEnd(id(8L), id(7L), id(6L), TurnEndOutcome.COMPLETED, true, null, null);
    new EntryPath(
        List.of(
            root,
            inputStart,
            user,
            assistant,
            inputEnd,
            thresholdAfterContinuation,
            thresholdResult,
            thresholdEnd));
  }

  @Test
  void thresholdCompactionCannotBorrowForeignContinuation() {
    Entry root = root(settings("root"));
    Entry inputStart = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));
    Entry assistant = assistantMessage(id(4L), id(3L));
    Entry inputEnd = turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, true, null, null);
    BranchSettings compactionSettings = settings("turn");
    Entry foreignCompaction =
        new Entry(
            id(6L),
            SESSION_ID,
            id(5L),
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                compactionSettings,
                id(999L),
                null,
                null,
                new CompactionStart(
                    CompactionPhase.FULL,
                    CompactionTrigger.THRESHOLD,
                    compactionSettings.model(),
                    id(1L),
                    null,
                    null)),
            time(id(6L)));
    Entry result = compactionResult(id(7L), id(6L));
    Entry end = turnEnd(id(8L), id(7L), id(6L), TurnEndOutcome.COMPLETED, true, null, null);

    // reducer 的 owner barrier 与 Store 物化校验一致：不能借兄弟 Thread 的 continuation。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root, inputStart, user, assistant, inputEnd, foreignCompaction, result, end)));
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
                    turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn")),
                    userMessage(id(3L), id(2L)),
                    compactionResult(id(4L), id(3L)),
                    turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    // COMPACTION turn 绝不消费 USER / CUSTOM / 普通 ASSISTANT 结果。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(id(2L), id(1L), settings("turn")),
                    userMessage(id(3L), id(2L)),
                    compactionResult(id(4L), id(3L)),
                    turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(id(2L), id(1L), settings("turn")),
                    customMessage(id(3L), id(2L)),
                    compactionResult(id(4L), id(3L)),
                    turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(id(2L), id(1L), settings("turn")),
                    assistantMessage(id(3L), id(2L)),
                    turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    // COMPACTION turn 不能以普通 ASSISTANT 结果完成。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    compactionStart(id(2L), id(1L), settings("turn")),
                    assistantMessage(id(3L), id(2L)),
                    turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
  }

  @Test
  void acceptsFailedAndStoppedCompactionBarriers() {
    Entry root = root(settings("root"));
    // 失败压缩：ASSISTANT_ERROR + FAILED。
    new EntryPath(
        List.of(
            root,
            compactionStart(id(2L), id(1L), settings("turn")),
            assistantError(id(3L), id(2L)),
            turnEnd(
                id(4L), id(3L), id(2L), TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null)));
    // 停止压缩：ASSISTANT_ABORTED + STOPPED。
    new EntryPath(
        List.of(
            root,
            compactionStart(id(2L), id(1L), settings("turn")),
            assistantAborted(id(3L), id(2L)),
            turnEnd(
                id(4L), id(3L), id(2L), TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, id(1L))));
  }

  @Test
  void acceptsFailedStoppedAndCancelledOutcomes() {
    Entry root = root(settings("root"));

    new EntryPath(
        List.of(
            root,
            turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn")),
            userMessage(id(3L), id(2L)),
            assistantError(id(4L), id(3L)),
            turnEnd(
                id(5L), id(4L), id(2L), TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null)));

    new EntryPath(
        List.of(
            root,
            turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn")),
            userMessage(id(3L), id(2L)),
            assistantAborted(id(4L), id(3L)),
            turnEnd(
                id(5L), id(4L), id(2L), TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, id(1L))));

    new EntryPath(
        List.of(
            root,
            turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn")),
            turnEnd(
                id(3L),
                id(2L),
                id(2L),
                TurnEndOutcome.CANCELLED,
                TurnEndReason.HISTORY_CUT,
                null)));

    new EntryPath(
        List.of(
            root,
            turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn")),
            userMessage(id(3L), id(2L)),
            turnEnd(
                id(4L),
                id(3L),
                id(2L),
                TurnEndOutcome.CANCELLED,
                TurnEndReason.HISTORY_CUT,
                null)));

    new EntryPath(
        List.of(
            root,
            turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn")),
            userMessage(id(3L), id(2L)),
            assistantMessage(id(4L), id(3L), "call-1:read"),
            toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read"),
            turnEnd(
                id(6L), id(5L), id(2L), TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, id(1L))));
  }

  @Test
  void acceptsPartialToolPrefixesAtAnyHead() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));
    Entry assistant = assistantMessage(id(4L), id(3L), "call-1:read", "call-2:grep");
    Entry tool0 = toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read");

    new EntryPath(List.of(root, start));
    new EntryPath(List.of(root, start, user));
    new EntryPath(List.of(root, start, user, assistant));
    new EntryPath(List.of(root, start, user, assistant, tool0));
  }

  @Test
  void rejectsEntriesOutsideOpenTurn() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry end =
        turnEnd(id(3L), id(2L), id(2L), TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);

    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, userMessage(id(2L), id(1L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, customMessage(id(2L), id(1L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, end, userMessage(id(4L), id(3L)))));
  }

  @Test
  void rejectsInputPhaseViolations() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(root, start, toolResult(id(3L), id(2L), 0, id(99L), "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, assistantError(id(3L), id(2L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, assistantAborted(id(3L), id(2L)))));
  }

  @Test
  void rejectsInputTurnWithoutInputBeforeAssistantResult() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, assistantMessage(id(3L), id(2L)))));
  }

  @Test
  void rejectsRepeatedOrLateAssistantResults() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));
    Entry assistant = assistantMessage(id(4L), id(3L));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(List.of(root, start, user, assistant, assistantMessage(id(5L), id(4L)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(List.of(root, start, user, assistant, assistantAborted(id(5L), id(4L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, assistantError(id(5L), id(4L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, userMessage(id(5L), id(4L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, user, assistant, customMessage(id(5L), id(4L)))));
  }

  @Test
  void rejectsMessagesInContinuationTurns() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.CONTINUATION, settings("turn"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, userMessage(id(3L), id(2L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, start, customMessage(id(3L), id(2L)))));

    // Continuation 无需 input 即偿还上一条 TURN_END.continueModel 的义务。
    new EntryPath(
        List.of(
            root,
            start,
            assistantMessage(id(3L), id(2L)),
            turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, null, null)));
    new EntryPath(
        List.of(
            root,
            turnStart(id(2L), id(1L), TurnStartReason.CONTINUATION, settings("turn")),
            assistantError(id(3L), id(2L)),
            turnEnd(
                id(4L), id(3L), id(2L), TurnEndOutcome.FAILED, TurnEndReason.TURN_FAILED, null)));
    new EntryPath(
        List.of(
            root,
            turnStart(id(2L), id(1L), TurnStartReason.CONTINUATION, settings("turn")),
            assistantAborted(id(3L), id(2L)),
            turnEnd(
                id(4L), id(3L), id(2L), TurnEndOutcome.STOPPED, TurnEndReason.USER_STOP, id(1L))));
  }

  @Test
  void rejectsToolResultsWithoutMatchingAssistantMessage() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L)),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantAborted(id(4L), id(3L)),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantError(id(4L), id(3L)),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read"))));
  }

  @Test
  void rejectsNonPrefixOrMismatchedToolResults() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read"),
                    toolResult(id(5L), id(4L), 1, id(4L), "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read", "call-2:grep"),
                    toolResult(id(5L), id(4L), 1, id(4L), "call-2", "grep"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read"),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-9", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read"),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-1", "grep"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read"),
                    toolResult(id(5L), id(4L), 0, id(99L), "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read"),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read"),
                    toolResult(id(6L), id(5L), 0, id(4L), "call-1", "read"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read"),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read"),
                    toolResult(id(6L), id(5L), 1, id(4L), "call-1", "read"))));
  }

  @Test
  void rejectsTurnEndOutcomeViolations() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    turnEnd(id(4L), id(3L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantAborted(id(4L), id(3L)),
                    turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantError(id(4L), id(3L)),
                    turnEnd(id(5L), id(4L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read", "call-2:grep"),
                    toolResult(id(5L), id(4L), 0, id(4L), "call-1", "read"),
                    turnEnd(id(6L), id(5L), id(2L), TurnEndOutcome.COMPLETED, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    turnEnd(
                        id(4L),
                        id(3L),
                        id(2L),
                        TurnEndOutcome.STOPPED,
                        TurnEndReason.USER_STOP,
                        id(1L)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L), "call-1:read"),
                    turnEnd(
                        id(5L),
                        id(4L),
                        id(2L),
                        TurnEndOutcome.STOPPED,
                        TurnEndReason.USER_STOP,
                        id(1L)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantAborted(id(4L), id(3L)),
                    turnEnd(
                        id(5L),
                        id(4L),
                        id(2L),
                        TurnEndOutcome.FAILED,
                        TurnEndReason.TURN_FAILED,
                        null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    start,
                    user,
                    assistantMessage(id(4L), id(3L)),
                    turnEnd(
                        id(5L),
                        id(4L),
                        id(2L),
                        TurnEndOutcome.FAILED,
                        TurnEndReason.TURN_FAILED,
                        null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    turnStart(id(2L), id(1L), TurnStartReason.CONTINUATION, settings("turn")),
                    turnEnd(
                        id(3L),
                        id(2L),
                        id(2L),
                        TurnEndOutcome.FAILED,
                        TurnEndReason.TURN_FAILED,
                        null))));
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
            id(2L),
            id(2L),
            id(1L),
            new TurnStartPayload(TurnStartReason.INPUT, settings("turn"), OWNER_THREAD_ID),
            time(id(2L)));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, other)));
  }

  @Test
  void rejectsRootNotFirstAndMultipleRoots() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry secondRoot =
        new Entry(id(3L), SESSION_ID, null, new RootPayload(settings("other")), time(id(3L)));

    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(start, root)));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, secondRoot)));
  }

  @Test
  void rejectsBrokenParentChainAndDuplicateIds() {
    Entry root = root(settings("root"));
    Entry broken =
        new Entry(
            id(3L),
            SESSION_ID,
            id(5L),
            new TurnStartPayload(TurnStartReason.INPUT, settings("t"), OWNER_THREAD_ID),
            time(id(3L)));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, broken)));

    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry duplicate =
        new Entry(
            id(2L),
            SESSION_ID,
            id(2L),
            new TurnStartPayload(TurnStartReason.INPUT, settings("t"), OWNER_THREAD_ID),
            time(id(3L)));
    assertThrows(
        IllegalArgumentException.class, () -> new EntryPath(List.of(root, start, duplicate)));
  }

  @Test
  void rejectsCreatedAtBeforeParent() {
    Entry root = root(settings("root"));
    Entry child =
        new Entry(
            id(2L),
            SESSION_ID,
            id(1L),
            new TurnStartPayload(TurnStartReason.INPUT, settings("turn"), OWNER_THREAD_ID),
            BASE.minusSeconds(1));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, child)));
  }

  @Test
  void rejectsSecondOpenTurnStart() {
    Entry root = root(settings("root"));
    Entry first = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("first"));
    Entry second = turnStart(id(3L), id(2L), TurnStartReason.INPUT, settings("second"));
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, first, second)));
  }

  @Test
  void rejectsTurnEndWithoutOpenTurnStart() {
    Entry root = root(settings("root"));
    Entry user = userMessage(id(2L), id(1L));
    Entry end = turnEnd(id(3L), id(2L), id(2L), TurnEndOutcome.COMPLETED, null, null);
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, user, end)));
  }

  @Test
  void rejectsTurnEndWithWrongTurnStartReference() {
    Entry root = root(settings("root"));
    Entry start = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry end =
        turnEnd(id(3L), id(2L), id(99L), TurnEndOutcome.CANCELLED, TurnEndReason.HISTORY_CUT, null);
    assertThrows(IllegalArgumentException.class, () -> new EntryPath(List.of(root, start, end)));
  }

  @Test
  void customEntriesAreTransparentToTurnGrammar() {
    Entry root = root(settings("root"));
    Entry customBeforeTurn = customEntry(id(2L), id(1L), "goal");
    Entry start = turnStart(id(3L), id(2L), TurnStartReason.INPUT, settings("turn"));
    Entry customInsideTurn = customEntry(id(4L), id(3L), "goal");
    Entry user = userMessage(id(5L), id(4L));
    Entry assistant = assistantMessage(id(6L), id(5L));
    Entry customAfterAssistant = customEntry(id(7L), id(6L), "goal");
    Entry end = turnEnd(id(8L), id(7L), id(3L), TurnEndOutcome.COMPLETED, null, null);
    Entry customAfterTurn = customEntry(id(9L), id(8L), "goal");

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
    Entry custom = customEntry(id(2L), id(1L), "goal");
    EntryPath path = new EntryPath(List.of(root, custom));
    assertEquals(custom, path.head());
    assertEquals(Optional.empty(), path.openTurnStart());
  }

  @Test
  void modelAttemptFailuresAreConsecutiveAndPrecedeTheAssistantResult() {
    Entry root = root(settings("root"));
    Entry turn = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    Entry user = userMessage(id(3L), id(2L));
    Entry failure1 = modelAttemptFailure(id(4L), id(3L), 1);
    Entry failure2 = modelAttemptFailure(id(5L), id(4L), 2);
    Entry assistant = assistantMessage(id(6L), id(5L));
    Entry end = turnEnd(id(7L), id(6L), id(2L), TurnEndOutcome.COMPLETED, null, null);

    EntryPath path = new EntryPath(List.of(root, turn, user, failure1, failure2, assistant, end));
    assertEquals(end, path.head());
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, turn, user, modelAttemptFailure(id(4L), id(3L), 2))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntryPath(
                List.of(
                    root,
                    turn,
                    user,
                    assistantMessage(id(4L), id(3L)),
                    modelAttemptFailure(id(5L), id(4L), 1))));
  }

  @Test
  void modelAttemptFailureIsRejectedWithoutInputOrInsideCompaction() {
    Entry root = root(settings("root"));
    Entry inputTurn = turnStart(id(2L), id(1L), TurnStartReason.INPUT, settings("turn"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, inputTurn, modelAttemptFailure(id(3L), id(2L), 1))));

    Entry compaction = compactionStart(id(2L), id(1L), settings("turn"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntryPath(List.of(root, compaction, modelAttemptFailure(id(3L), id(2L), 1))));
  }

  private static final UUID SESSION_ID = id(1L);

  private static Entry root(BranchSettings settings) {
    return new Entry(id(1L), SESSION_ID, null, new RootPayload(settings), BASE);
  }

  private static Entry turnStart(
      UUID id, UUID parentId, TurnStartReason reason, BranchSettings settings) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new TurnStartPayload(reason, settings, OWNER_THREAD_ID),
        time(id));
  }

  private static Entry turnEnd(
      UUID id,
      UUID parentId,
      UUID turnStartEntryId,
      TurnEndOutcome outcome,
      TurnEndReason reason,
      UUID closeRequestId) {
    return turnEnd(id, parentId, turnStartEntryId, outcome, false, reason, closeRequestId);
  }

  private static Entry turnEnd(
      UUID id,
      UUID parentId,
      UUID turnStartEntryId,
      TurnEndOutcome outcome,
      boolean continueModel,
      TurnEndReason reason,
      UUID closeRequestId) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new TurnEndPayload(turnStartEntryId, outcome, continueModel, reason, closeRequestId),
        time(id));
  }

  private static Entry userMessage(UUID id, UUID parentId) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi"))),
            null,
            null),
        time(id));
  }

  private static Entry customMessage(UUID id, UUID parentId) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new CustomMessagePayload(
            CustomMessagePayload.CORE_CONTRIBUTOR_ID,
            CustomMessagePayload.CORE_CUSTOM_TYPE,
            CustomMessagePayload.CORE_RENDERER_KEY,
            new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent("sys"))),
            CustomMessagePayload.CORE_DETAILS_JSON),
        time(id));
  }

  private static Entry customEntry(UUID id, UUID parentId, String customType) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new CustomEntryPayload("com.example.goal", customType, 1, "{\"s\":1}"),
        time(id));
  }

  private static Entry assistantMessage(UUID id, UUID parentId, String... toolCalls) {
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
    GenerationStopReason stopReason =
        toolCalls.length == 0 ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, contents), metadata(stopReason), null),
        time(id));
  }

  private static Entry toolResult(
      UUID id,
      UUID parentId,
      int ordinal,
      UUID assistantEntryId,
      String toolCallId,
      String toolName) {
    return new Entry(
        id,
        SESSION_ID,
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

  private static Entry compactionStart(UUID id, UUID parentId, BranchSettings settings) {
    return compactionStart(
        id, parentId, settings, CompactionPhase.FULL, CompactionTrigger.THRESHOLD, null, null);
  }

  private static Entry compactionStart(
      UUID id,
      UUID parentId,
      BranchSettings settings,
      CompactionPhase phase,
      CompactionTrigger trigger,
      UUID turnPrefixStartEntryId,
      UUID historyCompactionEntryId) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new TurnStartPayload(
            TurnStartReason.COMPACTION,
            settings,
            OWNER_THREAD_ID,
            null,
            null,
            new CompactionStart(
                phase,
                trigger,
                settings.model(),
                id(1L),
                turnPrefixStartEntryId,
                historyCompactionEntryId)),
        time(id));
  }

  private static Entry compactionResult(UUID id, UUID parentId) {
    return new Entry(id, SESSION_ID, parentId, new CompactionPayload("summary"), time(id));
  }

  private static Entry assistantError(UUID id, UUID parentId) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new AssistantErrorPayload(new AssistantError("MODEL_FAILED", "down"), null),
        time(id));
  }

  private static Entry modelAttemptFailure(UUID id, UUID parentId, int attempt) {
    Instant failedAt = time(id);
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new ModelAttemptFailurePayload(
            new ModelAttemptSnapshot(attempt, attempt, "partial-" + attempt, ""),
            new AssistantError("TRANSIENT", "down-" + attempt),
            failedAt.plusSeconds(1)),
        failedAt);
  }

  private static Entry assistantAborted(UUID id, UUID parentId) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new AssistantAbortedPayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("partial")))),
        time(id));
  }

  private static AssistantMessageMetadata metadata(GenerationStopReason reason) {
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

  private static Instant time(UUID id) {
    return BASE.plusSeconds(id.getLeastSignificantBits());
  }

  private static BranchSettings settings(String agentName) {
    return settings(null, agentName);
  }

  private static BranchSettings settings(EnvironmentBinding environment, String agentName) {
    return new BranchSettings(
        environment, agentName, new ModelSelection("anthropic", "claude-sonnet", "default"));
  }
}
