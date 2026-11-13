package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;

/** Validates the pending and resolved durable compaction turn shapes. */
class CompactionStartTest {

  @Test
  void acceptsPendingAndResolvedRunGroup() {
    CompactionStart pending = CompactionStart.pending(CompactionTrigger.MANUAL, id(4L));
    assertNull(pending.execution());
    assertNull(pending.outputBudget());
    assertNull(pending.childThreadId());
    assertNull(pending.joinInvocationId());

    CompactionStart resolved =
        new CompactionStart(
            CompactionTrigger.MANUAL,
            new ExecutionSelection("fake", "{}"),
            4096L,
            id(4L),
            id(100L),
            id(101L));

    assertEquals(4096L, resolved.outputBudget());
    assertEquals(id(100L), resolved.childThreadId());
    assertEquals(id(101L), resolved.joinInvocationId());
  }

  @Test
  void rejectsPartialRunGroup() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionTrigger.MANUAL,
                new ExecutionSelection("fake", "{}"),
                null,
                id(4L),
                id(100L),
                id(101L)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionStart(
                CompactionTrigger.MANUAL,
                new ExecutionSelection("fake", "{}"),
                -1L,
                id(4L),
                id(100L),
                id(101L)));
  }
}
