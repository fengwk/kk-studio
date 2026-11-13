package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;

/** Validates that preparation freezes only the compaction trigger and legal cut identity. */
class CompactionPreparationTest {

  @Test
  void pendingStartContainsTriggerAndCutPoint() {
    CompactionPreparation preparation = new CompactionPreparation(CompactionTrigger.MANUAL, id(4L));

    assertEquals(
        CompactionStart.pending(CompactionTrigger.MANUAL, id(4L)), preparation.pendingStart());
  }

  @Test
  void rejectsMissingTriggerOrCutPoint() {
    assertThrows(NullPointerException.class, () -> new CompactionPreparation(null, id(4L)));
    assertThrows(
        NullPointerException.class,
        () -> new CompactionPreparation(CompactionTrigger.MANUAL, null));
  }
}
