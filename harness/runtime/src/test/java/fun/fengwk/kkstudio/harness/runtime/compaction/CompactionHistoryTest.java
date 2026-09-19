package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
      new BranchSettings("agent", new ModelSelection("provider", "model", "variant"), null);

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
            new CompactionStart(
                CompactionPhase.FULL,
                CompactionTrigger.MANUAL,
                SETTINGS.model(),
                ROOT_ID,
                null,
                null)));
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
        GenerationStopReason.COMPLETE,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
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

  private static Entry entry(UUID entryId, UUID parentId, EntryPayload payload) {
    return new Entry(entryId, SESSION_ID, parentId, payload, NOW);
  }

  private static ThreadState thread(UUID headEntryId) {
    return new ThreadState(
        THREAD_ID, SESSION_ID, headEntryId, CREATION_REQUEST_HASH, "main", false, 1, 0, NOW, NOW);
  }
}
