package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

/** CompactionStart 的 phase/anchor 最小形状。 */
class CompactionStartTest {

  private static final ModelSelection MODEL = new ModelSelection("p", "m", "v");

  @Test
  void acceptsFullHistoryPairedAndDirectPrefixShapes() {
    CompactionStart full =
        new CompactionStart(
            CompactionPhase.FULL, CompactionTrigger.MANUAL, MODEL, id(4L), null, null);
    CompactionStart history =
        new CompactionStart(
            CompactionPhase.HISTORY, CompactionTrigger.THRESHOLD, MODEL, id(4L), id(3L), null);
    CompactionStart pairedPrefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            MODEL,
            id(4L),
            id(3L),
            id(8L));
    CompactionStart directPrefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX, CompactionTrigger.OVERFLOW, MODEL, id(4L), id(3L), null);

    assertNull(full.turnPrefixStartEntryId());
    assertEquals(id(3L), history.turnPrefixStartEntryId());
    assertEquals(id(8L), pairedPrefix.historyCompactionEntryId());
    assertNull(directPrefix.historyCompactionEntryId());
  }

  @Test
  void rejectsPhaseAnchorMismatches() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, MODEL, id(4L), id(3L), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.HISTORY, CompactionTrigger.THRESHOLD, MODEL, id(4L), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.HISTORY,
                CompactionTrigger.THRESHOLD,
                MODEL,
                id(4L),
                id(3L),
                id(8L)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionPhase.TURN_PREFIX,
                CompactionTrigger.THRESHOLD,
                MODEL,
                id(4L),
                null,
                id(8L)));
  }
}
