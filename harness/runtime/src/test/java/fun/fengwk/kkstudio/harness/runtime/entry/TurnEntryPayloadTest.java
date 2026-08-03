package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** TURN_START/TURN_END 的类型归属、ID 和 continuation 约束。 */
class TurnEntryPayloadTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings(
          null,
          "coding",
          new ModelSelection("anthropic", "claude-sonnet", "default"),
          "high",
          List.of("read"));

  @Test
  void createsTypedTurnBoundaries() {
    TurnStartEntryPayload start = new TurnStartEntryPayload(TurnStartReason.INPUT, SETTINGS);
    TurnEndEntryPayload end =
        new TurnEndEntryPayload(123L, TurnEndOutcome.COMPLETED, true, null, null);

    assertEquals(EntryType.TURN_START, start.type());
    assertEquals(EntryType.TURN_END, end.type());
    assertEquals(SETTINGS, start.settings());
    assertEquals(123L, end.turnStartEntryId());
  }

  @Test
  void rejectsInvalidTurnEndReferencesAndContinuation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnEndEntryPayload(0L, TurnEndOutcome.COMPLETED, false, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnEndEntryPayload(123L, TurnEndOutcome.FAILED, true, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnEndEntryPayload(123L, TurnEndOutcome.STOPPED, false, " ", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnEndEntryPayload(123L, TurnEndOutcome.CANCELLED, false, null, " close"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndEntryPayload(123L, TurnEndOutcome.CANCELLED, false, "r".repeat(129), null));
  }

  @Test
  void requiresNonNullTypedFields() {
    assertThrows(NullPointerException.class, () -> new TurnStartEntryPayload(null, SETTINGS));
    assertThrows(
        NullPointerException.class, () -> new TurnStartEntryPayload(TurnStartReason.INPUT, null));
    assertThrows(
        NullPointerException.class, () -> new TurnEndEntryPayload(123L, null, false, null, null));
  }
}
