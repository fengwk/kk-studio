package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;

import java.time.Instant;
import java.util.List;

/** CompactionTurns 从 enclosing turn 派生 complete/result/anchor 事实。 */
class CompactionTurnsTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("p", "m", "v"), null);
  private static final Instant NOW = Instant.EPOCH;

  @Test
  void scansCompleteTurnAndFindsExactResult() {
    EntryPath path = completedPath(CompactionPhase.FULL, false);

    List<CompactionTurns.CompactionTurn> turns = CompactionTurns.scan(path);
    assertEquals(1, turns.size());
    CompactionTurns.CompactionTurn turn = turns.getFirst();
    assertTrue(turn.complete());
    assertTrue(turn.completed());
    assertEquals(CompactionPhase.FULL, turn.phase());
    assertEquals(CompactionTrigger.THRESHOLD, turn.trigger());
    assertEquals(turn, CompactionTurns.latestComplete(path).orElseThrow());
    assertEquals(turn, CompactionTurns.ofResult(path, id(3L)));
    assertEquals(id(3L), CompactionTurns.entryAt(path, turn.resultIndex()).id());
    assertThrows(IllegalStateException.class, () -> CompactionTurns.ofResult(path, id(99L)));
  }

  @Test
  void completedHistoryIsPartialAndConstructorRejectsInvalidFacts() {
    EntryPath history = completedPath(CompactionPhase.HISTORY, true);
    CompactionTurns.CompactionTurn turn = CompactionTurns.scan(history).getFirst();
    assertFalse(turn.complete());
    assertTrue(turn.completed());
    assertTrue(CompactionTurns.latestComplete(history).isEmpty());

    TurnStartPayload normal = new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, id(10L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CompactionTurns.CompactionTurn(normal, null, null, 1, -1, -1));
    TurnStartPayload compaction = (TurnStartPayload) history.entries().get(1).payload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionTurns.CompactionTurn(
                compaction, new CompactionPayload("summary"), null, 1, 2, -1));
  }

  private static EntryPath completedPath(CompactionPhase phase, boolean continueModel) {
    CompactionStart start =
        new CompactionStart(
            phase,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(1L),
            phase == CompactionPhase.FULL ? null : id(1L),
            null);
    return new EntryPath(
        List.of(
            new Entry(id(1L), id(100L), null, new RootPayload(SETTINGS), NOW),
            new Entry(
                id(2L),
                id(100L),
                id(1L),
                new TurnStartPayload(
                    TurnStartReason.COMPACTION, SETTINGS, id(10L), 100_000, 16_384, start),
                NOW),
            new Entry(id(3L), id(100L), id(2L), new CompactionPayload("summary"), NOW),
            new Entry(
                id(4L),
                id(100L),
                id(3L),
                new TurnEndPayload(id(2L), TurnEndOutcome.COMPLETED, continueModel, null, null),
                NOW)));
  }
}
