package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;

import java.time.Duration;

/** ThreadProcessorConfig 部署配置校验：至少 1ms 的正 resolveFailureDelay、非空 compaction。 */
class ThreadProcessorConfigTest {

  private static final CompactionConfig COMPACTION = new CompactionConfig(20_000);

  @Test
  void acceptsMillisecondResolutionDelay() {
    ThreadProcessorConfig config =
        new ThreadProcessorConfig(Duration.ofMillis(7_500), () -> COMPACTION);
    assertEquals(Duration.ofMillis(7_500), config.resolveFailureDelay());
    assertEquals(COMPACTION, config.compactionProvider().compactionConfig());
  }

  @Test
  void rejectsMissingOrNonPositiveResolveFailureDelay() {
    assertThrows(
        NullPointerException.class, () -> new ThreadProcessorConfig(null, () -> COMPACTION));
    for (Duration delay :
        new Duration[] {
          Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(999_999), Duration.ofSeconds(-5)
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ThreadProcessorConfig(delay, () -> COMPACTION),
          "delay " + delay);
    }
  }

  @Test
  void rejectsNullCompactionConfigProvider() {
    assertThrows(
        NullPointerException.class, () -> new ThreadProcessorConfig(Duration.ofSeconds(1), null));
  }
}
