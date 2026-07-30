package fun.fengwk.kkstudio.core.ai.runtime.model.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorkerConfig;

import java.time.Duration;

class HarnessModelWorkerConfigurationTest {

  /** The composition root passes deployment values unchanged into the Runtime worker contract. */
  @Test
  void createsConfigFromModelWorkerProperties() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setModelWorkerLeaseDuration(Duration.ofSeconds(45));
    properties.setModelWorkerHeartbeatInterval(Duration.ofSeconds(15));
    properties.setModelWorkerActivityFlushInterval(Duration.ofMillis(250));

    ModelWorkerConfig config = new HarnessModelWorkerConfiguration().modelWorkerConfig(properties);

    assertEquals(Duration.ofSeconds(45), config.workerLeaseDuration());
    assertEquals(Duration.ofSeconds(15), config.heartbeatInterval());
    assertEquals(Duration.ofMillis(250), config.activityFlushInterval());
  }

  /** Invalid lease, heartbeat, and activity cadences fail before a worker can be composed. */
  @Test
  void rejectsInvalidModelWorkerConfigValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelWorkerConfig(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelWorkerConfig(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelWorkerConfig(
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofNanos(999_999)));
  }
}
