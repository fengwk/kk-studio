package fun.fengwk.kkstudio.harness.runtime.store.testing;

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
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 内存窄读取与完整路径同语义，但不通过 loadPath 取得当前状态。 */
class InMemoryEntryCurrentReadTest {

  @Test
  void loadCurrentStopsAtRequiredAnchorsForOpenAndClosedHeads() {
    HarnessStore store = new InMemoryHarnessStore();
    StoreTestSupport.Baseline baseline = StoreTestSupport.seedThreadBaseline(store);
    BranchSettings live = settings("live");
    BranchSettings applied = settings("applied");
    UUID start =
        insert(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new TurnStartPayload(TurnStartReason.INPUT, live, baseline.threadId()),
            1);
    UUID user = insert(store, baseline.sessionId(), start, userPayload(false), 2);
    UUID end =
        insert(
            store,
            baseline.sessionId(),
            user,
            new TurnEndPayload(
                start, TurnEndOutcome.CANCELLED, false, TurnEndReason.CANCELLED, null),
            3);
    UUID settingsEntry =
        insert(
            store, baseline.sessionId(), end, new SettingsPayload(applied, baseline.threadId()), 4);
    UUID head =
        insert(
            store,
            baseline.sessionId(),
            settingsEntry,
            ForkPayload.branch(baseline.rootEntryId()),
            5);

    store.transaction(
        tx -> {
          EntryCurrentState closed = tx.entries().loadCurrent(head);
          assertEquals(head, closed.head().id());
          assertEquals(end, closed.headIgnoringTrailingControlEntries().id());
          assertTrue(closed.openTurnStart().isEmpty());
          assertEquals(start, closed.referencedTurnStartEntry().id());
          assertEquals(applied, closed.baseSettings());

          EntryCurrentState open = tx.entries().loadCurrent(user);
          assertEquals(user, open.headIgnoringTrailingControlEntries().id());
          assertEquals(start, open.openTurnStart().orElseThrow().id());
          assertNull(open.referencedTurnStartEntry());
          assertEquals(live, open.baseSettings());
          return null;
        });
  }

  @Test
  void compactionSettingsDoNotReplaceBranchSettings() {
    HarnessStore store = new InMemoryHarnessStore();
    StoreTestSupport.Baseline baseline = StoreTestSupport.seedThreadBaseline(store);
    BranchSettings live = settings("live");
    UUID start =
        insert(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new TurnStartPayload(TurnStartReason.INPUT, live, baseline.threadId()),
            1);
    UUID user = insert(store, baseline.sessionId(), start, userPayload(false), 2);
    UUID end =
        insert(
            store,
            baseline.sessionId(),
            user,
            new TurnEndPayload(
                start, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
            3);
    UUID compaction =
        insert(
            store,
            baseline.sessionId(),
            end,
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                settings("poison"),
                baseline.threadId(),
                null,
                null,
                CompactionStart.pending(
                    CompactionPhase.FULL,
                    CompactionTrigger.MANUAL,
                    baseline.rootEntryId(),
                    null,
                    null),
                null),
            4);
    UUID summary =
        insert(store, baseline.sessionId(), compaction, new CompactionPayload("summary", null), 5);
    UUID compactionEnd =
        insert(
            store,
            baseline.sessionId(),
            summary,
            new TurnEndPayload(
                compaction, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
            6);

    EntryCurrentState open = store.transaction(tx -> tx.entries().loadCurrent(compaction));
    assertEquals(compaction, open.openTurnStart().orElseThrow().id());
    assertNull(open.referencedTurnStartEntry());
    assertEquals(live, open.baseSettings());

    EntryCurrentState state = store.transaction(tx -> tx.entries().loadCurrent(compactionEnd));

    assertEquals(compactionEnd, state.headIgnoringTrailingControlEntries().id());
    assertTrue(state.openTurnStart().isEmpty());
    assertEquals(compaction, state.referencedTurnStartEntry().id());
    assertEquals(live, state.baseSettings());
  }

  @Test
  void metricsAndAncestorQueriesUseChainMembership() {
    HarnessStore store = new InMemoryHarnessStore();
    StoreTestSupport.Baseline left = StoreTestSupport.seedThreadBaseline(store);
    UUID input =
        insert(
            store,
            left.sessionId(),
            left.rootEntryId(),
            new TurnStartPayload(TurnStartReason.INPUT, settings("live"), left.threadId()),
            1);
    UUID user = insert(store, left.sessionId(), input, userPayload(false), 2);
    UUID assistant = insert(store, left.sessionId(), user, assistantPayload(), 3);
    UUID inputEnd =
        insert(
            store,
            left.sessionId(),
            assistant,
            new TurnEndPayload(
                input, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
            4);
    UUID continuation =
        insert(
            store,
            left.sessionId(),
            inputEnd,
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings("live"), left.threadId()),
            5);
    UUID continuationEnd =
        insert(
            store,
            left.sessionId(),
            continuation,
            new TurnEndPayload(
                continuation, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
            6);
    UUID stop =
        insert(
            store,
            left.sessionId(),
            continuationEnd,
            new TurnStartPayload(TurnStartReason.STOP, settings("live"), left.threadId()),
            7);
    StoreTestSupport.Baseline right = StoreTestSupport.seedThreadBaseline(store);

    store.transaction(
        tx -> {
          assertEquals(new EntryPathMetrics(2, 1), tx.entries().loadPathMetrics(stop));
          assertTrue(tx.entries().isAncestor(stop, left.rootEntryId()));
          assertTrue(tx.entries().isAncestor(stop, stop));
          assertFalse(tx.entries().isAncestor(stop, right.rootEntryId()));
          assertFalse(tx.entries().isAncestor(stop, id(404L)));
          assertThrows(IllegalArgumentException.class, () -> tx.entries().loadCurrent(id(405L)));
          assertThrows(
              IllegalArgumentException.class, () -> tx.entries().loadPathMetrics(id(405L)));
          assertThrows(
              IllegalArgumentException.class, () -> tx.entries().isAncestor(id(405L), stop));
          return null;
        });
  }

  @Test
  void rootOnlyCurrentStateMatchesRootSettings() {
    HarnessStore store = new InMemoryHarnessStore();
    StoreTestSupport.Baseline baseline = StoreTestSupport.seedThreadBaseline(store);

    EntryCurrentState state =
        store.transaction(tx -> tx.entries().loadCurrent(baseline.rootEntryId()));

    assertEquals(baseline.rootEntryId(), state.head().id());
    assertTrue(state.openTurnStart().isEmpty());
    assertNull(state.referencedTurnStartEntry());
    assertEquals(
        state.baseSettings(),
        store.transaction(tx -> tx.entries().loadBranchSettings(baseline.rootEntryId())));
  }

  private static UUID insert(
      HarnessStore store, UUID sessionId, UUID parentId, EntryPayload payload, int seconds) {
    return store.transaction(
        tx -> {
          UUID entryId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      entryId,
                      sessionId,
                      parentId,
                      payload,
                      Instant.ofEpochMilli(1000L + seconds * 1000L)));
          return entryId;
        });
  }

  private static MessagePayload userPayload(boolean toolCall) {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                toolCall
                    ? new ToolCallMessageContent("user-call", "bash", "bash", "{}")
                    : new TextMessageContent("hello"))),
        null,
        null);
  }

  private static MessagePayload assistantPayload() {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent("call-1", "bash", "bash", "{}"))),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE, new ModelUsage(1L, 1L, 0L, 0L, Map.of(), 0L, 2L)),
        null);
  }

  private static BranchSettings settings(String agent) {
    return new BranchSettings(agent, ModelSelection.model("claude", "default"), null);
  }
}
