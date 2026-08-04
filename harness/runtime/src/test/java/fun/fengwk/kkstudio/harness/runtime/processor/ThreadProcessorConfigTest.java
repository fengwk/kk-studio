package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** ThreadProcessorConfig 部署配置校验：正 stepLimit、至少 1ms 的正 resolveFailureDelay。 */
class ThreadProcessorConfigTest {

  private static final ProcessorLeaseConfig LEASE =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));

  @Test
  void acceptsPositiveStepLimitAndMillisecondResolutionDelay() {
    ThreadProcessorConfig config = new ThreadProcessorConfig(LEASE, 3, Duration.ofMillis(7_500));
    assertEquals(3, config.stepLimit());
    assertEquals(Duration.ofMillis(7_500), config.resolveFailureDelay());
    assertEquals(LEASE, config.leaseConfig());
  }

  @Test
  void rejectsNullLeaseConfig() {
    assertThrows(
        NullPointerException.class,
        () -> new ThreadProcessorConfig(null, 1, Duration.ofSeconds(1)));
  }

  @Test
  void rejectsNonPositiveStepLimit() {
    for (int stepLimit : new int[] {0, -1}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ThreadProcessorConfig(LEASE, stepLimit, Duration.ofSeconds(1)),
          "stepLimit " + stepLimit);
    }
  }

  @Test
  void rejectsMissingOrNonPositiveResolveFailureDelay() {
    assertThrows(NullPointerException.class, () -> new ThreadProcessorConfig(LEASE, 1, null));
    for (Duration delay :
        new Duration[] {
          Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(999_999), Duration.ofSeconds(-5)
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ThreadProcessorConfig(LEASE, 1, delay),
          "delay " + delay);
    }
  }
}
