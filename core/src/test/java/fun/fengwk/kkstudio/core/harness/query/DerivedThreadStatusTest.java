package fun.fengwk.kkstudio.core.harness.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/** 派生状态规则：RUNNING > WAITING > RUNNABLE > IDLE。 */
class DerivedThreadStatusTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void runningWhenProcessorLeaseIsActive() {
    HarnessQueryRow row = base();
    row.setProcessorToken("tok");
    row.setProcessorUntil(OffsetDateTime.ofInstant(NOW.plusSeconds(30), ZoneOffset.UTC));
    row.setRunnable(true);
    row.setHasQueuedInput(true);
    assertEquals(DerivedThreadStatus.RUNNING, DerivedThreadStatus.derive(row, NOW));
    assertTrue(
        DerivedThreadStatus.isProcessing(row.getProcessorToken(), row.getProcessorUntil(), NOW));
  }

  @Test
  void waitingWhenQueuedInputOrActiveInvocationOrOpenInteraction() {
    assertEquals(
        DerivedThreadStatus.WAITING,
        DerivedThreadStatus.derive(withFlag(row -> row.setHasQueuedInput(true)), NOW));
    assertEquals(
        DerivedThreadStatus.WAITING,
        DerivedThreadStatus.derive(withFlag(row -> row.setHasActiveModel(true)), NOW));
    assertEquals(
        DerivedThreadStatus.WAITING,
        DerivedThreadStatus.derive(withFlag(row -> row.setHasActiveTool(true)), NOW));
    assertEquals(
        DerivedThreadStatus.WAITING,
        DerivedThreadStatus.derive(withFlag(row -> row.setHasOpenInteraction(true)), NOW));
  }

  @Test
  void runnableWhenMarkedAndNoWaiters() {
    HarnessQueryRow row = base();
    row.setRunnable(true);
    assertEquals(DerivedThreadStatus.RUNNABLE, DerivedThreadStatus.derive(row, NOW));
  }

  @Test
  void idleOtherwise() {
    HarnessQueryRow row = base();
    row.setRunnable(false);
    row.setProcessorToken("expired");
    row.setProcessorUntil(OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
    assertEquals(DerivedThreadStatus.IDLE, DerivedThreadStatus.derive(row, NOW));
    assertFalse(
        DerivedThreadStatus.isProcessing(row.getProcessorToken(), row.getProcessorUntil(), NOW));
  }

  private static HarnessQueryRow base() {
    HarnessQueryRow row = new HarnessQueryRow();
    row.setId(1L);
    row.setRunnable(false);
    row.setHasQueuedInput(false);
    row.setHasActiveModel(false);
    row.setHasActiveTool(false);
    row.setHasOpenInteraction(false);
    return row;
  }

  private static HarnessQueryRow withFlag(Consumer<HarnessQueryRow> mutator) {
    HarnessQueryRow row = base();
    mutator.accept(row);
    return row;
  }
}
