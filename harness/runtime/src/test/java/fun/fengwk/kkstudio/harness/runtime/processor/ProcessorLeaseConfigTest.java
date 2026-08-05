package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ProcessorLeaseConfigTest {

  @Test
  void acceptsPositiveWholeMillisecondLeaseAndHeartbeat() {
    ProcessorLeaseConfig config =
        new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));

    assertEquals(Duration.ofSeconds(30), config.leaseDuration());
    assertEquals(Duration.ofSeconds(5), config.heartbeatInterval());
  }

  @Test
  void rejectsSubMillisecondRemaindersBeforeTheyCanReachTheStore() {
    Duration oneAndHalfMilliseconds = Duration.ofNanos(1_500_000);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(oneAndHalfMilliseconds, Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ofMillis(2), oneAndHalfMilliseconds));
  }
}
