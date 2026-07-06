package fun.fengwk.kkstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * ModelRetryConfig 的退避计算边界测试。
 *
 * @author fengwk
 */
public class ModelRetryConfigTest {

  /** 校验 retryCount 非正数或 baseDelay 缺失时不延迟。 */
  @Test
  public void testReturnsZeroWhenRetryCountOrBaseDelayDisablesBackoff() {
    assertEquals(
        Duration.ZERO,
        ModelRetryConfig.builder().baseDelay(Duration.ofMillis(10)).build().nextDelay(0));
    assertEquals(Duration.ZERO, ModelRetryConfig.builder().build().nextDelay(1));
    assertEquals(
        Duration.ZERO, ModelRetryConfig.builder().baseDelay(Duration.ZERO).build().nextDelay(1));
  }

  /** 校验指数退避会按 multiplier 增长并受 maxDelay 截断。 */
  @Test
  public void testExponentialBackoffIsCappedByMaxDelay() {
    ModelRetryConfig config =
        ModelRetryConfig.builder()
            .baseDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofMillis(250))
            .multiplier(2D)
            .build();

    assertEquals(Duration.ofMillis(100), config.nextDelay(1));
    assertEquals(Duration.ofMillis(200), config.nextDelay(2));
    assertEquals(Duration.ofMillis(250), config.nextDelay(3));
  }

  /** 校验非法 multiplier 使用默认 2 倍退避。 */
  @Test
  public void testNonPositiveMultiplierFallsBackToDouble() {
    ModelRetryConfig config =
        ModelRetryConfig.builder()
            .baseDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofMillis(500))
            .multiplier(0D)
            .build();

    assertEquals(Duration.ofMillis(200), config.nextDelay(2));
  }
}
