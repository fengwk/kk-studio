package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;

/** CompactionPayload 领域不变量：阶段/complete 组合矩阵、切分引用与 summary/token 合法性。 */
class CompactionPayloadTest {

  @Test
  void acceptsFullHistoryAndTurnPrefixCanonicalShapes() {
    CompactionPayload full =
        new CompactionPayload(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, true, "full", 2L, 4L, null);
    assertEquals(EntryType.COMPACTION, full.type());
    assertTrue(full.complete());
    assertNull(full.turnPrefixStartEntryId());

    CompactionPayload history =
        new CompactionPayload(
            CompactionPhase.HISTORY,
            CompactionTrigger.OVERFLOW,
            500L,
            false,
            "history",
            2L,
            4L,
            3L);
    assertEquals(CompactionPhase.HISTORY, history.phase());
    assertEquals(CompactionTrigger.OVERFLOW, history.trigger());
    assertEquals(3L, history.turnPrefixStartEntryId());

    CompactionPayload prefix =
        new CompactionPayload(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            0L,
            true,
            "prefix",
            2L,
            4L,
            3L);
    assertEquals(0L, prefix.tokensBefore());
  }

  @Test
  void rejectsPhaseCompleteMismatches() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.HISTORY, CompactionTrigger.THRESHOLD, 500L, true, "x", 2L, 4L, 3L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, false, "x", 2L, 4L, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.TURN_PREFIX,
                CompactionTrigger.THRESHOLD,
                500L,
                false,
                "x",
                2L,
                4L,
                3L));
  }

  @Test
  void rejectsSplitPhasesWithoutPrefixAndFullWithPrefix() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.HISTORY,
                CompactionTrigger.THRESHOLD,
                500L,
                false,
                "x",
                2L,
                4L,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.TURN_PREFIX,
                CompactionTrigger.THRESHOLD,
                500L,
                true,
                "x",
                2L,
                4L,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, true, "x", 2L, 4L, 3L));
  }

  @Test
  void rejectsBlankSummaryNegativeTokensAndNonPositiveIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, true, " ", 2L, 4L, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, -1L, true, "x", 2L, 4L, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, true, "x", 0L, 4L, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, true, "x", 2L, -3L, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPayload(
                CompactionPhase.TURN_PREFIX,
                CompactionTrigger.THRESHOLD,
                500L,
                true,
                "x",
                2L,
                4L,
                -1L));
  }
}
