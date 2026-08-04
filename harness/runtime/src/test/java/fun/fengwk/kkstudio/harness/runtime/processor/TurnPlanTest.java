package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.util.List;

/** TurnPlan 不可变快照校验：positive id、非负 cutoff、非空引用、防御性列表拷贝与 deferred-message 推导。 */
class TurnPlanTest {

  @Test
  void validPlanIsAcceptedAndListsAreDefensivelyCopied() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath candidatePath = path(store, baseline.threadId());
    List<Entry> entries = candidatePath.entries();
    TurnPlan plan =
        new TurnPlan(
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            false,
            1,
            List.of(),
            List.of(),
            entries,
            candidatePath,
            99,
            100,
            false,
            TurnStartReason.INPUT);
    assertEquals(baseline.threadId(), plan.threadId());
    assertEquals(100, plan.candidateHeadEntryId());
    assertEquals(TurnStartReason.INPUT, plan.reason());
    assertEquals(entries, plan.candidateEntries());
    assertEquals(List.of(), plan.plannedCommands());
    assertEquals(List.of(), plan.consumedCommands());
    assertFalse(plan.hasDeferredMessages());
  }

  @Test
  void hasDeferredMessagesDerivesFromPlannedMinusConsumed() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    long messageCommandId =
        seedCommand(
            store,
            baseline.threadId(),
            new UserMessageCommandPayload(ThreadProcessorTestBase.userMessage("hi")));
    ThreadCommand message =
        ThreadProcessorTestSupport.command(store, baseline.threadId(), messageCommandId);
    EntryPath candidatePath = path(store, baseline.threadId());
    // continuation 保留 message 命令（未消费）-> deferred wake。
    TurnPlan withDeferred =
        new TurnPlan(
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            false,
            1,
            List.of(message),
            List.of(),
            candidatePath.entries(),
            candidatePath,
            99,
            100,
            false,
            TurnStartReason.CONTINUATION);
    assertTrue(withDeferred.hasDeferredMessages());
    // 同一命令已被消费 -> 不再需要 wake。
    TurnPlan consumed =
        new TurnPlan(
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            false,
            1,
            List.of(message),
            List.of(message),
            candidatePath.entries(),
            candidatePath,
            99,
            100,
            false,
            TurnStartReason.CONTINUATION);
    assertFalse(consumed.hasDeferredMessages());
  }

  @Test
  void rejectsNonPositiveIdsAndNegativeCutoff() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath candidatePath = path(store, baseline.threadId());
    long threadId = baseline.threadId();
    long sessionId = baseline.sessionId();
    long sourceHeadEntryId = baseline.rootEntryId();
    assertThrows(
        IllegalArgumentException.class,
        () -> validPlan(0, sessionId, sourceHeadEntryId, 0, candidatePath, 99, 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> validPlan(threadId, 0, sourceHeadEntryId, 0, candidatePath, 99, 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> validPlan(threadId, sessionId, 0, 0, candidatePath, 99, 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> validPlan(threadId, sessionId, sourceHeadEntryId, -1, candidatePath, 99, 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> validPlan(threadId, sessionId, sourceHeadEntryId, 0, candidatePath, 0, 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> validPlan(threadId, sessionId, sourceHeadEntryId, 0, candidatePath, 99, 0));
  }

  @Test
  void rejectsNullReferences() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath candidatePath = path(store, baseline.threadId());
    assertThrows(
        NullPointerException.class,
        () ->
            new TurnPlan(
                baseline.threadId(),
                baseline.sessionId(),
                baseline.rootEntryId(),
                false,
                0,
                List.of(),
                List.of(),
                List.of(),
                null,
                99,
                100,
                false,
                TurnStartReason.INPUT));
    assertThrows(
        NullPointerException.class,
        () ->
            new TurnPlan(
                baseline.threadId(),
                baseline.sessionId(),
                baseline.rootEntryId(),
                false,
                0,
                List.of(),
                List.of(),
                List.of(),
                candidatePath,
                99,
                100,
                false,
                null));
  }

  private static TurnPlan validPlan(
      long threadId,
      long sessionId,
      long sourceHeadEntryId,
      long cutoffSequence,
      EntryPath candidatePath,
      long turnStartEntryId,
      long candidateHeadEntryId) {
    return new TurnPlan(
        threadId,
        sessionId,
        sourceHeadEntryId,
        false,
        cutoffSequence,
        List.of(),
        List.of(),
        candidatePath.entries(),
        candidatePath,
        turnStartEntryId,
        candidateHeadEntryId,
        false,
        TurnStartReason.INPUT);
  }
}
