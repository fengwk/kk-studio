package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;

import java.time.Duration;

/** ThreadProcessorConfig 部署配置校验：至少 1ms 的正 resolveFailureDelay、非空 compaction。 */
class ThreadProcessorConfigTest {

  private static final ProcessorLeaseConfig LEASE =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));

  private static final CompactionConfig COMPACTION = new CompactionConfig(20_000, null);

  @Test
  void acceptsMillisecondResolutionDelay() {
    ThreadProcessorConfig config =
        new ThreadProcessorConfig(LEASE, Duration.ofMillis(7_500), COMPACTION);
    assertEquals(Duration.ofMillis(7_500), config.resolveFailureDelay());
    assertEquals(LEASE, config.leaseConfig());
    assertEquals(COMPACTION, config.compaction());
  }

  @Test
  void rejectsNullLeaseConfig() {
    assertThrows(
        NullPointerException.class,
        () -> new ThreadProcessorConfig(null, Duration.ofSeconds(1), COMPACTION));
  }

  @Test
  void rejectsMissingOrNonPositiveResolveFailureDelay() {
    assertThrows(
        NullPointerException.class, () -> new ThreadProcessorConfig(LEASE, null, COMPACTION));
    for (Duration delay :
        new Duration[] {
          Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(999_999), Duration.ofSeconds(-5)
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ThreadProcessorConfig(LEASE, delay, COMPACTION),
          "delay " + delay);
    }
  }

  @Test
  void rejectsNullCompactionConfig() {
    assertThrows(
        NullPointerException.class,
        () -> new ThreadProcessorConfig(LEASE, Duration.ofSeconds(1), null));
  }
}
