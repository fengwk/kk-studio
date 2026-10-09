package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.util.UUID;

/** CompactionStart 的 phase/anchor 最小形状与 run group 不变量。 */
class CompactionStartTest {

  private static final ModelSelection MODEL = new ModelSelection("p", "m", "v");
  private static final Long BUDGET = 4096L;
  private static final UUID CHILD_ID = id(100L);
  private static final UUID JOIN_ID = id(101L);

  @Test
  void acceptsFullHistoryPairedAndDirectPrefixShapes() {
    CompactionStart full =
        new CompactionStart(
            CompactionPhase.FULL,
            CompactionTrigger.MANUAL,
            MODEL,
            BUDGET,
            id(4L),
            null,
            null,
            CHILD_ID,
            JOIN_ID);
    CompactionStart history =
        new CompactionStart(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            MODEL,
            BUDGET,
            id(4L),
            id(3L),
            null,
            CHILD_ID,
            JOIN_ID);
    CompactionStart pairedPrefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            MODEL,
            BUDGET,
            id(4L),
            id(3L),
            id(8L),
            CHILD_ID,
            JOIN_ID);
    CompactionStart directPrefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.OVERFLOW,
            MODEL,
            BUDGET,
            id(4L),
            id(3L),
            null,
            CHILD_ID,
            JOIN_ID);

    assertNull(full.turnPrefixStartEntryId());
    assertEquals(id(3L), history.turnPrefixStartEntryId());
    assertEquals(id(8L), pairedPrefix.historyCompactionEntryId());
    assertNull(directPrefix.historyCompactionEntryId());
    assertEquals(BUDGET, full.outputBudget());
    assertEquals(CHILD_ID, full.childThreadId());
    assertEquals(JOIN_ID, full.joinInvocationId());
  }

  @Test
  void acceptsPendingFactory() {
    CompactionStart pending =
        CompactionStart.pending(CompactionPhase.FULL, CompactionTrigger.MANUAL, id(4L), null, null);
    assertNull(pending.executionModel());
    assertNull(pending.outputBudget());
    assertNull(pending.childThreadId());
    assertNull(pending.joinInvocationId());
  }

  @Test
  void rejectsPartialRunGroup() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.FULL,
                CompactionTrigger.MANUAL,
                MODEL,
                null,
                id(4L),
                null,
                null,
                CHILD_ID,
                JOIN_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.FULL,
                CompactionTrigger.MANUAL,
                MODEL,
                -1L,
                id(4L),
                null,
                null,
                CHILD_ID,
                JOIN_ID));
  }

  @Test
  void rejectsPhaseAnchorMismatches() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.FULL,
                CompactionTrigger.THRESHOLD,
                MODEL,
                BUDGET,
                id(4L),
                id(3L),
                null,
                CHILD_ID,
                JOIN_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.HISTORY,
                CompactionTrigger.THRESHOLD,
                MODEL,
                BUDGET,
                id(4L),
                null,
                null,
                CHILD_ID,
                JOIN_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.HISTORY,
                CompactionTrigger.THRESHOLD,
                MODEL,
                BUDGET,
                id(4L),
                id(3L),
                id(8L),
                CHILD_ID,
                JOIN_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.TURN_PREFIX,
                CompactionTrigger.THRESHOLD,
                MODEL,
                BUDGET,
                id(4L),
                null,
                id(8L),
                CHILD_ID,
                JOIN_ID));
  }
}
