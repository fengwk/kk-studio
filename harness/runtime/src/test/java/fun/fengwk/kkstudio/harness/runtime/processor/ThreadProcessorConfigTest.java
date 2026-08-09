package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;

import java.time.Duration;

/** ThreadProcessorConfig 部署配置校验：正 stepLimit、至少 1ms 的正 resolveFailureDelay、非空 compaction。 */
class ThreadProcessorConfigTest {

  private static final ProcessorLeaseConfig LEASE =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));

  private static final CompactionConfig COMPACTION = new CompactionConfig(true, 16_384, 20_000);

  @Test
  void acceptsPositiveStepLimitAndMillisecondResolutionDelay() {
    ThreadProcessorConfig config =
        new ThreadProcessorConfig(LEASE, 3, Duration.ofMillis(7_500), COMPACTION);
    assertEquals(3, config.stepLimit());
    assertEquals(Duration.ofMillis(7_500), config.resolveFailureDelay());
    assertEquals(LEASE, config.leaseConfig());
    assertEquals(COMPACTION, config.compaction());
  }

  @Test
  void rejectsNullLeaseConfig() {
    assertThrows(
        NullPointerException.class,
        () -> new ThreadProcessorConfig(null, 1, Duration.ofSeconds(1), COMPACTION));
  }

  @Test
  void rejectsNonPositiveStepLimit() {
    for (int stepLimit : new int[] {0, -1}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ThreadProcessorConfig(LEASE, stepLimit, Duration.ofSeconds(1), COMPACTION),
          "stepLimit " + stepLimit);
    }
  }

  @Test
  void rejectsMissingOrNonPositiveResolveFailureDelay() {
    assertThrows(
        NullPointerException.class, () -> new ThreadProcessorConfig(LEASE, 1, null, COMPACTION));
    for (Duration delay :
        new Duration[] {
          Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(999_999), Duration.ofSeconds(-5)
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ThreadProcessorConfig(LEASE, 1, delay, COMPACTION),
          "delay " + delay);
    }
  }

  @Test
  void rejectsNullCompactionConfig() {
    assertThrows(
        NullPointerException.class,
        () -> new ThreadProcessorConfig(LEASE, 1, Duration.ofSeconds(1), null));
  }
}
