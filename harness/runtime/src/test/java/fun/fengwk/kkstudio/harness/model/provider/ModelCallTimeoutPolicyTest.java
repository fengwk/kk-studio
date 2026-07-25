package fun.fengwk.kkstudio.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** Provider 级模型调用超时策略的产品默认值和构造边界。 */
class ModelCallTimeoutPolicyTest {

  @Test
  void defaultsToThirtyMinuteTotalAndTwoMinuteIdleTimeout() {
    assertEquals(Duration.ofMinutes(30), ModelCallTimeoutPolicy.DEFAULT.modelCallTimeout());
    assertEquals(Duration.ofSeconds(120), ModelCallTimeoutPolicy.DEFAULT.modelCallIdleTimeout());
  }

  @Test
  void rejectsNonPositiveTimeouts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelCallTimeoutPolicy(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelCallTimeoutPolicy(Duration.ofSeconds(1), Duration.ofMillis(-1)));
  }
}
