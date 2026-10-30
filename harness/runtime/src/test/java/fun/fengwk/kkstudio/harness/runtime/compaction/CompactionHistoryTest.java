package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link CompactionHistory} 共享历史投影的所有权与 continuation 边界测试。 */
class CompactionHistoryTest {

  private static final UUID SESSION_ID = id(100);
  private static final UUID THREAD_ID = id(101);
  private static final UUID OTHER_THREAD_ID = id(102);
  private static final UUID ROOT_ID = id(1);
  private static final UUID TURN_START_ID = id(2);
  private static final UUID USER_ID = id(3);
  private static final UUID ASSISTANT_ID = id(4);
  private static final UUID TURN_END_ID = id(5);
  private static final UUID COMPACTION_START_ID = id(6);
  private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", ModelSelection.model("model", "variant"), null);

  @Test
  void pendingContinuationRequiresOwnedHistoryAndKnownCurrentCompactionStart() {
    // 共享投影必须只恢复当前 Thread 拥有的 continuation，并对未知 current start fail closed。
    EntryPath ownedPath = pathWithPendingContinuation(THREAD_ID);
    ThreadState thread = thread(ownedPath.head().id());
    assertTrue(
        CompactionHistory.hasPendingOwnedContinuation(thread, ownedPath, COMPACTION_START_ID));

    EntryPath foreignPath = pathWithPendingContinuation(OTHER_THREAD_ID);
    assertFalse(
        CompactionHistory.hasPendingOwnedContinuation(thread, foreignPath, COMPACTION_START_ID));

    assertThrows(
        IllegalStateException.class,
        () -> CompactionHistory.hasPendingOwnedContinuation(thread, ownedPath, id(999)));

    EntryPath noHistoryPath = new EntryPath(List.of(root(), compactionStart(ROOT_ID)));
    assertFalse(
        CompactionHistory.hasPendingOwnedContinuation(thread, noHistoryPath, COMPACTION_START_ID));
  }

  @Test
  void closedTurnNavigationAndTurnResultFollowThePath() {
    UUID compactionResultId = id(7);
    UUID compactionEndId = id(8);
    UUID continuationStartId = id(9);
    UUID continuationAssistantId = id(10);
    UUID continuationEndId = id(11);
    EntryPath path =
        new EntryPath(
            List.of(
                root(),
                entry(
                    TURN_START_ID,
                    ROOT_ID,
                    new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, THREAD_ID)),
                message(USER_ID, TURN_START_ID, AgentMessageRole.USER, "input"),
                message(ASSISTANT_ID, USER_ID, AgentMessageRole.ASSISTANT, "answer"),
                turnEnd(TURN_END_ID, ASSISTANT_ID, TURN_START_ID, true),
                compactionStartEntry(
                    COMPACTION_START_ID, TURN_END_ID, THREAD_ID, CompactionTrigger.OVERFLOW),
                entry(
                    compactionResultId,
                    COMPACTION_START_ID,
                    new CompactionPayload("summary", null)),
                turnEnd(compactionEndId, compactionResultId, COMPACTION_START_ID, true),
                entry(
                    continuationStartId,
                    compactionEndId,
                    new TurnStartPayload(TurnStartReason.CONTINUATION, SETTINGS, THREAD_ID)),
                message(
                    continuationAssistantId,
                    continuationStartId,
                    AgentMessageRole.ASSISTANT,
                    "continued"),
                turnEnd(continuationEndId, continuationAssistantId, continuationStartId, false)));

    CompactionHistory.ClosedTurn latest = CompactionHistory.latestClosedTurn(path);
    assertNotNull(latest);
    assertEquals(continuationStartId, latest.start().id());
    CompactionHistory.ClosedTurn previous = CompactionHistory.previousClosedTurn(path, latest);
    assertNotNull(previous);
    assertEquals(COMPACTION_START_ID, previous.start().id());
    CompactionHistory.ClosedTurn first = CompactionHistory.previousClosedTurn(path, previous);
    assertNotNull(first);
    assertEquals(TURN_START_ID, first.start().id());
    assertNull(
        CompactionHistory.previousClosedTurn(
            path,
            new CompactionHistory.ClosedTurn(
                entry(id(999), null, new RootPayload(SETTINGS)), first.end())));

    assertTrue(CompactionHistory.compactionTurnAtStart(path, COMPACTION_START_ID).complete());
    assertThrows(
        IllegalStateException.class,
        () -> CompactionHistory.compactionTurnAtStart(path, TURN_START_ID));
    assertEquals(
        continuationAssistantId,
        CompactionHistory.turnResultEntry(path, path.entries().get(8)).id());
    assertNull(CompactionHistory.turnResultEntry(path, path.entries().get(5)));
    assertNull(
        CompactionHistory.turnResultEntry(path, entry(id(998), null, new RootPayload(SETTINGS))));

    EntryPath openPath =
        new EntryPath(
            List.of(
                root(),
                compactionStartEntry(
                    COMPACTION_START_ID, ROOT_ID, THREAD_ID, CompactionTrigger.OVERFLOW)));
    assertNull(CompactionHistory.latestClosedTurn(openPath));
  }

  @Test
  void pendingContinuationWalksClosedCompactionTurnsAndRejectsForeignOwners() {
    UUID compactionResultId = id(20);
    UUID compactionEndId = id(21);
    UUID currentStartId = id(22);
    EntryPath ownedPath =
        pendingPath(THREAD_ID, compactionResultId, compactionEndId, currentStartId);
    ThreadState thread = thread(ownedPath.head().id());
    assertTrue(CompactionHistory.hasPendingOwnedContinuation(thread, ownedPath, currentStartId));

    EntryPath foreignPath =
        pendingPath(OTHER_THREAD_ID, compactionResultId, compactionEndId, currentStartId);
    assertFalse(CompactionHistory.hasPendingOwnedContinuation(thread, foreignPath, currentStartId));

    EntryPath closedWithoutContinuation =
        new EntryPath(
            List.of(
                root(),
                entry(
                    TURN_START_ID,
                    ROOT_ID,
                    new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, THREAD_ID)),
                message(USER_ID, TURN_START_ID, AgentMessageRole.USER, "input"),
                message(ASSISTANT_ID, USER_ID, AgentMessageRole.ASSISTANT, "answer"),
                turnEnd(TURN_END_ID, ASSISTANT_ID, TURN_START_ID, false),
                compactionStartEntry(
                    currentStartId, TURN_END_ID, THREAD_ID, CompactionTrigger.OVERFLOW)));
    assertFalse(
        CompactionHistory.hasPendingOwnedContinuation(
            thread, closedWithoutContinuation, currentStartId));
  }

  @Test
  void overflowRecoveryRequiresCompleteOverflowCompactionAndContinuation() {
    EntryPath retryPath = overflowRetryPath(CompactionTrigger.OVERFLOW);
    CompactionHistory.ClosedTurn latest = CompactionHistory.latestClosedTurn(retryPath);
    assertNotNull(latest);
    assertTrue(CompactionHistory.isOverflowRecoveryRetry(retryPath, latest));

    EntryPath manualPath =
        new EntryPath(
            List.of(
                root(),
                compactionStartEntry(
                    COMPACTION_START_ID, ROOT_ID, THREAD_ID, CompactionTrigger.MANUAL),
                entry(id(45), COMPACTION_START_ID, new CompactionPayload("summary", null)),
                turnEnd(id(46), id(45), COMPACTION_START_ID, false)));
    CompactionHistory.ClosedTurn manualLatest = CompactionHistory.latestClosedTurn(manualPath);
    assertNotNull(manualLatest);
    assertFalse(CompactionHistory.isOverflowRecoveryRetry(manualPath, manualLatest));

    EntryPath inputPath =
        new EntryPath(
            List.of(
                root(),
                entry(
                    TURN_START_ID,
                    ROOT_ID,
                    new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, THREAD_ID)),
                message(USER_ID, TURN_START_ID, AgentMessageRole.USER, "input"),
                message(ASSISTANT_ID, USER_ID, AgentMessageRole.ASSISTANT, "answer"),
                turnEnd(TURN_END_ID, ASSISTANT_ID, TURN_START_ID, false)));
    CompactionHistory.ClosedTurn inputLatest = CompactionHistory.latestClosedTurn(inputPath);
    assertNotNull(inputLatest);
    assertFalse(CompactionHistory.isOverflowRecoveryRetry(inputPath, inputLatest));

    EntryPath noPreviousPath =
        new EntryPath(
            List.of(
                root(),
                entry(
                    CONTINUATION_START_ID,
                    ROOT_ID,
                    new TurnStartPayload(TurnStartReason.CONTINUATION, SETTINGS, THREAD_ID)),
                message(
                    CONT_ASSISTANT_ID,
                    CONTINUATION_START_ID,
                    AgentMessageRole.ASSISTANT,
                    "continued"),
                turnEnd(CONT_END_ID, CONT_ASSISTANT_ID, CONTINUATION_START_ID, false)));
    CompactionHistory.ClosedTurn noPreviousLatest =
        CompactionHistory.latestClosedTurn(noPreviousPath);
    assertNotNull(noPreviousLatest);
    assertFalse(CompactionHistory.isOverflowRecoveryRetry(noPreviousPath, noPreviousLatest));
  }

  @Test
  void contextWallUsesOverflowErrorsAndResponsePressure() {
    TurnStartPayload start =
        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, THREAD_ID, 100, 10, null, null);
    assertFalse(CompactionHistory.isContextWall(start, null));
    assertTrue(
        CompactionHistory.isContextWall(
            start,
            entry(
                id(30),
                ROOT_ID,
                new AssistantErrorPayload(
                    new AssistantError(ProviderErrorKind.OVERFLOW.name(), "overflow"), null))));
    assertFalse(
        CompactionHistory.isContextWall(
            start,
            entry(
                id(31),
                ROOT_ID,
                new AssistantErrorPayload(
                    new AssistantError(ProviderErrorKind.AUTHENTICATION.name(), "auth"), null))));
    assertTrue(
        CompactionHistory.isContextWall(
            start,
            entry(
                id(32),
                ROOT_ID,
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("answer"))),
                    assistantMetadata(100, 0),
                    null))));
    assertFalse(
        CompactionHistory.isContextWall(
            start, message(id(33), ROOT_ID, AgentMessageRole.USER, "input")));
    assertFalse(
        CompactionHistory.isContextWall(
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, THREAD_ID),
            entry(
                id(34),
                ROOT_ID,
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("answer"))),
                    assistantMetadata(100, 0),
                    null))));
  }

  private static EntryPath pendingPath(
      UUID compactionOwner, UUID resultId, UUID endId, UUID currentStartId) {
    return new EntryPath(
        List.of(
            root(),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, THREAD_ID)),
            message(USER_ID, TURN_START_ID, AgentMessageRole.USER, "input"),
            message(ASSISTANT_ID, USER_ID, AgentMessageRole.ASSISTANT, "answer"),
            turnEnd(TURN_END_ID, ASSISTANT_ID, TURN_START_ID, true),
            compactionStartEntry(
                COMPACTION_START_ID, TURN_END_ID, compactionOwner, CompactionTrigger.OVERFLOW),
            entry(resultId, COMPACTION_START_ID, new CompactionPayload("summary", null)),
            turnEnd(endId, resultId, COMPACTION_START_ID, true),
            compactionStartEntry(currentStartId, endId, THREAD_ID, CompactionTrigger.OVERFLOW)));
  }

  private static EntryPath overflowRetryPath(CompactionTrigger trigger) {
    UUID compactionResultId = id(40);
    UUID compactionEndId = id(41);
    UUID continuationStartId = id(42);
    UUID continuationAssistantId = id(43);
    return new EntryPath(
        List.of(
            root(),
            compactionStartEntry(COMPACTION_START_ID, ROOT_ID, THREAD_ID, trigger),
            entry(compactionResultId, COMPACTION_START_ID, new CompactionPayload("summary", null)),
            turnEnd(compactionEndId, compactionResultId, COMPACTION_START_ID, true),
            entry(
                continuationStartId,
                compactionEndId,
                new TurnStartPayload(TurnStartReason.CONTINUATION, SETTINGS, THREAD_ID)),
            message(
                continuationAssistantId,
                continuationStartId,
                AgentMessageRole.ASSISTANT,
                "continued"),
            turnEnd(id(44), continuationAssistantId, continuationStartId, false)));
  }

  private static final UUID CONTINUATION_START_ID = id(50);
  private static final UUID CONT_ASSISTANT_ID = id(51);
  private static final UUID CONT_END_ID = id(52);

  private static Entry compactionStartEntry(
      UUID entryId, UUID parentId, UUID ownerThreadId, CompactionTrigger trigger) {
    return entry(
        entryId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.COMPACTION,
            SETTINGS,
            ownerThreadId,
            null,
            null,
            CompactionStart.pending(CompactionPhase.FULL, trigger, ROOT_ID, null, null),
            null));
  }

  private static Entry turnEnd(
      UUID entryId, UUID parentId, UUID turnStartId, boolean continueModel) {
    return entry(
        entryId,
        parentId,
        new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, continueModel, null, null));
  }

  private static AssistantMessageMetadata assistantMetadata(long inputTokens, long outputTokens) {
    return new AssistantMessageMetadata(
        GenerationStopReason.COMPLETE,
        new ModelUsage(inputTokens, outputTokens, 0, 0, Map.of(), 0L, inputTokens + outputTokens));
  }

  private static EntryPath pathWithPendingContinuation(UUID ownerThreadId) {
    return new EntryPath(
        List.of(
            root(),
            entry(
                TURN_START_ID,
                ROOT_ID,
                new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, ownerThreadId)),
            message(USER_ID, TURN_START_ID, AgentMessageRole.USER, "input"),
            message(ASSISTANT_ID, USER_ID, AgentMessageRole.ASSISTANT, "answer"),
            entry(
                TURN_END_ID,
                ASSISTANT_ID,
                new TurnEndPayload(TURN_START_ID, TurnEndOutcome.COMPLETED, true, null, null)),
            compactionStart(TURN_END_ID)));
  }

  private static Entry root() {
    return entry(ROOT_ID, null, new RootPayload(SETTINGS));
  }

  private static Entry compactionStart(UUID parentId) {
    return entry(
        COMPACTION_START_ID,
        parentId,
        new TurnStartPayload(
            TurnStartReason.COMPACTION,
            SETTINGS,
            THREAD_ID,
            null,
            null,
            CompactionStart.pending(
                CompactionPhase.FULL, CompactionTrigger.MANUAL, ROOT_ID, null, null),
            null));
  }

  private static Entry message(UUID entryId, UUID parentId, AgentMessageRole role, String text) {
    return entry(
        entryId,
        parentId,
        new MessagePayload(
            new AgentMessage(role, List.of(new TextMessageContent(text))),
            role == AgentMessageRole.ASSISTANT ? assistantMetadata() : null,
            null));
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return new AssistantMessageMetadata(
        GenerationStopReason.COMPLETE, new ModelUsage(1, 1, 0, 0, Map.of(), 0L, 2L));
  }

  private static Entry entry(UUID entryId, UUID parentId, EntryPayload payload) {
    return new Entry(entryId, SESSION_ID, parentId, payload, NOW);
  }

  private static ThreadState thread(UUID headEntryId) {
    return new ThreadState(
        THREAD_ID,
        SESSION_ID,
        null,
        headEntryId,
        CREATION_REQUEST_HASH,
        "main",
        THREAD_ID,
        false,
        SpawnTrigger.USER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1,
        0,
        NOW,
        NOW);
  }
}
