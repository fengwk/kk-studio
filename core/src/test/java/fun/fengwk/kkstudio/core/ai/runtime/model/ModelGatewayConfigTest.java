package fun.fengwk.kkstudio.core.ai.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** 验证不可变的 {@link ModelGatewayConfig} record 及整毫秒约束。默认值现在由数据库 SystemSettings 装配，不再有静态 DEFAULT。 */
class ModelGatewayConfigTest {

  @Test
  void acceptsPiDefaultBusyRetryDelay() {
    // 与 SystemSettings.Tool.DEFAULT.modelGatewayBusyRetryMillis 一致的装配等价形式。
    assertEquals(
        Duration.ofSeconds(5), new ModelGatewayConfig(Duration.ofSeconds(5)).busyRetryDelay());
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
