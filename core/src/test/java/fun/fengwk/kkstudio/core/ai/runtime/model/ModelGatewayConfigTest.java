package fun.fengwk.kkstudio.core.ai.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** Validates the immutable {@link ModelGatewayConfig} record and its defaults. */
class ModelGatewayConfigTest {

  @Test
  void providesDefaultBusyRetryDelay() {
    assertEquals(Duration.ofSeconds(5), ModelGatewayConfig.DEFAULT.busyRetryDelay());
  }

  @Test
  void acceptsPositiveWholeMillisecondDelay() {
    assertEquals(
        Duration.ofMillis(250), new ModelGatewayConfig(Duration.ofMillis(250)).busyRetryDelay());
  }

  @Test
  void rejectsInvalidBusyRetryDelays() {
    assertThrows(NullPointerException.class, () -> new ModelGatewayConfig(null));
    assertThrows(IllegalArgumentException.class, () -> new ModelGatewayConfig(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelGatewayConfig(Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelGatewayConfig(Duration.ofNanos(1_500_000)));
  }
}
