package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.Lease;

import java.time.Instant;

/** HarnessThread 字段约束与 hasActiveProcessorAt 边界测试。 */
class HarnessThreadTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void nullLeaseMeansNoActiveProcessor() {
    HarnessThread thread = new HarnessThread(1L, 10L, 0L, true, 0L, 0L, null, NOW, NOW);

    assertFalse(thread.hasActiveProcessorAt(NOW));
    assertFalse(thread.hasActiveProcessorAt(NOW.plusSeconds(60)));
  }

  @Test
  void leaseActiveBeforeUntil() {
    Lease lease = new Lease("token", NOW.plusSeconds(60));
    HarnessThread thread = new HarnessThread(1L, 10L, 0L, false, 7L, 0L, lease, NOW, NOW);

    assertTrue(thread.hasActiveProcessorAt(NOW));
    assertTrue(thread.hasActiveProcessorAt(NOW.plusSeconds(59).plusMillis(999)));
    assertFalse(thread.hasActiveProcessorAt(NOW.plusSeconds(60)));
    assertFalse(thread.hasActiveProcessorAt(NOW.plusSeconds(120)));
  }

  @Test
  void hasActiveProcessorAtRejectsNullObservedAt() {
    Lease lease = new Lease("token", NOW.plusSeconds(60));
    HarnessThread thread = new HarnessThread(1L, 10L, 0L, false, 7L, 0L, lease, NOW, NOW);

    assertThrows(NullPointerException.class, () -> thread.hasActiveProcessorAt(null));
  }

  @Test
  void supportsBoundAndUnboundHeads() {
    HarnessThread unbound = new HarnessThread(1L, null, 0L, false, 0L, 0L, null, NOW, NOW);
    HarnessThread bound = new HarnessThread(2L, 10L, 0L, false, 0L, 0L, null, NOW, NOW);

    assertFalse(unbound.isBound());
    assertThrows(IllegalStateException.class, unbound::requireHeadEntryId);
    assertTrue(bound.isBound());
    assertTrue(bound.requireHeadEntryId() == 10L);
  }

  @Test
  void rejectsNonPositiveIdOrPresentHead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(0L, 10L, 0L, true, 0L, 0L, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 0L, 0L, true, 0L, 0L, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, -1L, 0L, true, 0L, 0L, null, NOW, NOW));
  }

  @Test
  void rejectsNegativeSequenceOrEpoch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 10L, -1L, true, 0L, 0L, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 10L, 0L, true, -1L, 0L, null, NOW, NOW));
  }

  @Test
  void rejectsInvertedTimestamps() {
    Instant earlier = NOW.minusSeconds(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessThread(1L, 10L, 0L, true, 0L, 0L, null, NOW, earlier));
  }
}
