package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;

import java.time.Instant;
import java.util.List;

/** Derives compaction completion from the result payload and enclosing turn end. */
class CompactionTurnsTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ExecutionSelection("fake", "{}"), null);
  private static final Instant NOW = Instant.EPOCH;

  @Test
  void scansCompleteTurnAndFindsExactResult() {
    EntryPath path = completedPath();

    List<CompactionTurns.CompactionTurn> turns = CompactionTurns.scan(path);
    assertEquals(1, turns.size());
    CompactionTurns.CompactionTurn turn = turns.getFirst();
    assertTrue(turn.complete());
    assertTrue(turn.completed());
    assertEquals(CompactionTrigger.THRESHOLD, turn.trigger());
    assertEquals(turn, CompactionTurns.latestComplete(path).orElseThrow());
    assertEquals(turn, CompactionTurns.ofResult(path, id(3L)));
    assertEquals(id(3L), CompactionTurns.entryAt(path, turn.resultIndex()).id());
    assertThrows(IllegalStateException.class, () -> CompactionTurns.ofResult(path, id(99L)));
  }

  @Test
  void constructorRejectsInvalidFacts() {
    TurnStartPayload normal = new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, id(10L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CompactionTurns.CompactionTurn(normal, null, null, 1, -1, -1));
    TurnStartPayload compaction = (TurnStartPayload) completedPath().entries().get(1).payload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionTurns.CompactionTurn(
                compaction, new CompactionPayload("summary", null), null, 1, 2, -1));
  }

  private static EntryPath completedPath() {
    CompactionStart start = CompactionStart.pending(CompactionTrigger.THRESHOLD, id(1L));
    return new EntryPath(
        List.of(
            new Entry(id(1L), id(100L), null, new RootPayload(SETTINGS), NOW),
            new Entry(
                id(2L),
                id(100L),
                id(1L),
                new TurnStartPayload(
                    TurnStartReason.COMPACTION, SETTINGS, id(10L), 100_000, 16_384, start, null),
                NOW),
            new Entry(id(3L), id(100L), id(2L), new CompactionPayload("summary", null), NOW),
            new Entry(
                id(4L),
                id(100L),
                id(3L),
                new TurnEndPayload(id(2L), TurnEndOutcome.COMPLETED, true, null, null),
                NOW)));
  }
}
