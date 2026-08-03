package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.Lease;

import java.time.Instant;

/** HarnessThread is always bound to a positive Session ROOT/head entry. */
class HarnessThreadTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void boundThreadTracksProcessorLease() {
    HarnessThread idle = new HarnessThread(1L, 10L, null, 0L, true, 0L, 0L, null, NOW, NOW);
    Lease lease = new Lease("token", NOW.plusSeconds(60));
    HarnessThread active = new HarnessThread(2L, 11L, null, 0L, false, 7L, 0L, lease, NOW, NOW);

    assertFalse(idle.hasActiveProcessorAt(NOW));
    assertTrue(active.hasActiveProcessorAt(NOW));
    assertFalse(active.hasActiveProcessorAt(NOW.plusSeconds(60)));
  }

  @Test
  void rejectsUnboundOrInvalidThreadState() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 0L, null, 0L, true, 0L, 0L, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 10L, null, -1L, true, 0L, 0L, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 10L, null, 0L, true, -1L, 0L, null, NOW, NOW));
  }

  @Test
  void rejectsNullObservedAtAndInvertedTimestamps() {
    HarnessThread thread = new HarnessThread(1L, 10L, null, 0L, true, 0L, 0L, null, NOW, NOW);

    assertThrows(NullPointerException.class, () -> thread.hasActiveProcessorAt(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 10L, null, 0L, true, 0L, 0L, null, NOW, NOW.minusSeconds(1)));
  }
}
