package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** ThreadState durable field and invariant checks. */
class ThreadStateTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void acceptsValidDurableFields() {
    ThreadState state = state(7L, 42L, true, 3L, 5L, CREATED);

    assertEquals(7L, state.id());
    assertEquals(42L, state.headEntryId());
    assertTrue(state.yoloEnabled());
    assertEquals(3L, state.nextCommandSequence());
    assertEquals(5L, state.revision());
    assertEquals(CREATED, state.createdAt());
    assertEquals(CREATED, state.updatedAt());

    ThreadState yoloDisabled = state(8L, 43L, false, 1L, 0L, CREATED.plusSeconds(2));
    assertFalse(yoloDisabled.yoloEnabled());
  }

  @Test
  void rejectsInvalidIdentityAndSequence() {
    assertThrows(IllegalArgumentException.class, () -> state(0L, 42L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(-1L, 42L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, 0L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, -1L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, 42L, false, 0L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, 42L, false, 1L, -1L, CREATED));
  }

  @Test
  void rejectsInvalidTimestamps() {
    assertThrows(NullPointerException.class, () -> state(7L, 42L, false, 1L, 0L, (Instant) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> state(7L, 42L, false, 1L, 0L, CREATED.minusSeconds(1)));
  }

  private static ThreadState state(
      long id,
      long headEntryId,
      boolean yoloEnabled,
      long nextCommandSequence,
      long revision,
      Instant updatedAt) {
    return new ThreadState(
        id, headEntryId, yoloEnabled, nextCommandSequence, revision, CREATED, updatedAt);
  }
}
