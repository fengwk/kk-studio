package fun.fengwk.kkstudio.core.harness.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/** 派生状态规则：RUNNING &gt; WAITING &gt; RUNNABLE &gt; UNBOUND &gt; IDLE。 */
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

  /** 一个静止且尚未绑定 head Entry 的 Thread 是 UNBOUND，而不是 IDLE。 */
  @Test
  void unboundWhenQuiescentWithoutHeadEntry() {
    HarnessQueryRow row = base();
    row.setHeadEntryId(null);
    assertEquals(DerivedThreadStatus.UNBOUND, DerivedThreadStatus.derive(row, NOW));
  }

  /** 执行事实优先于 UNBOUND：即使 head 为空，仍在跑的代际必须显示为真实执行状态。 */
  @Test
  void executionFactsOutrankUnbound() {
    HarnessQueryRow running = base();
    running.setHeadEntryId(null);
    running.setProcessorToken("tok");
    running.setProcessorUntil(OffsetDateTime.ofInstant(NOW.plusSeconds(30), ZoneOffset.UTC));
    assertEquals(DerivedThreadStatus.RUNNING, DerivedThreadStatus.derive(running, NOW));

    HarnessQueryRow waiting = base();
    waiting.setHeadEntryId(null);
    waiting.setHasOpenInteraction(true);
    assertEquals(DerivedThreadStatus.WAITING, DerivedThreadStatus.derive(waiting, NOW));

    HarnessQueryRow runnable = base();
    runnable.setHeadEntryId(null);
    runnable.setRunnable(true);
    assertEquals(DerivedThreadStatus.RUNNABLE, DerivedThreadStatus.derive(runnable, NOW));
  }

  /** 默认基线是一个已绑定 head Entry 的 Thread。 */
  private static HarnessQueryRow base() {
    HarnessQueryRow row = new HarnessQueryRow();
    row.setId(1L);
    row.setHeadEntryId(10L);
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
