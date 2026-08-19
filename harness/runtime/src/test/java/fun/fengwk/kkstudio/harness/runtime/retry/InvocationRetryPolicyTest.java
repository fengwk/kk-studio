package fun.fengwk.kkstudio.harness.runtime.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

/** 自动重试次数与退避计算的 Invocation 级值对象契约。 */
class InvocationRetryPolicyTest {

  @Test
  void defaultPolicyMatchesPiStyleExponentialDelays() {
    InvocationRetryPolicy policy =
        new InvocationRetryPolicy(
            3,
            InvocationRetryBackoffStrategy.EXPONENTIAL,
            Duration.ofSeconds(2),
            Duration.ofSeconds(60));

    assertEquals(3, policy.maxRetries());
    assertEquals(Duration.ofSeconds(2), policy.delayBeforeRetry(1));
    assertEquals(Duration.ofSeconds(4), policy.delayBeforeRetry(2));
    assertEquals(Duration.ofSeconds(8), policy.delayBeforeRetry(3));
    assertTrue(policy.allowsRetry(3));
    assertFalse(policy.allowsRetry(4));
  }

  @Test
  void fixedAndExponentialPoliciesRespectTheirDelayLimits() {
    InvocationRetryPolicy fixed =
        new InvocationRetryPolicy(
            3, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(2), Duration.ofSeconds(10));
    InvocationRetryPolicy exponential =
        new InvocationRetryPolicy(
            5,
            InvocationRetryBackoffStrategy.EXPONENTIAL,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));

    assertEquals(Duration.ofSeconds(2), fixed.delayBeforeRetry(1));
    assertEquals(Duration.ofSeconds(2), fixed.delayBeforeRetry(3));
    assertEquals(Duration.ofSeconds(2), exponential.delayBeforeRetry(1));
    assertEquals(Duration.ofSeconds(4), exponential.delayBeforeRetry(2));
    assertEquals(Duration.ofSeconds(5), exponential.delayBeforeRetry(3));
    assertEquals(Duration.ofSeconds(5), exponential.delayBeforeRetry(5));
  }

  @Test
  void rejectsInvalidRetryPolicyValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvocationRetryPolicy(
                -1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.EXPONENTIAL,
                Duration.ZERO,
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.EXPONENTIAL,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvocationRetryPolicy(
                    3,
                    InvocationRetryBackoffStrategy.EXPONENTIAL,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(60))
                .delayBeforeRetry(0));
  }

  /** 持久化 strategy 解析大小写无关，且与进程 locale 独立。 */
  @Test
  void parsesBackoffStrategyDeterministically() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals(
          InvocationRetryBackoffStrategy.FIXED,
          InvocationRetryBackoffStrategy.fromValue(" fixed "));
      assertEquals(
          InvocationRetryBackoffStrategy.EXPONENTIAL,
          InvocationRetryBackoffStrategy.fromValue("Exponential"));
    } finally {
      Locale.setDefault(previous);
    }
    assertThrows(
        IllegalArgumentException.class, () -> InvocationRetryBackoffStrategy.fromValue(" "));
    assertThrows(
        IllegalArgumentException.class, () -> InvocationRetryBackoffStrategy.fromValue("linear"));
  }
}
