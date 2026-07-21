package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** 自动重试次数与退避计算的值对象契约。 */
class ThreadRetryPolicyTest {

  @Test
  void defaultPolicyMatchesPiStyleExponentialDelays() {
    ThreadRetryPolicy policy = ThreadRetryPolicy.DEFAULT;

    assertEquals(3, policy.maxRetries());
    assertEquals(Duration.ofSeconds(2), policy.delayBeforeRetry(1));
    assertEquals(Duration.ofSeconds(4), policy.delayBeforeRetry(2));
    assertEquals(Duration.ofSeconds(8), policy.delayBeforeRetry(3));
    assertTrue(policy.allowsRetry(3));
    assertFalse(policy.allowsRetry(4));
  }

  @Test
  void fixedAndExponentialPoliciesRespectTheirDelayLimits() {
    ThreadRetryPolicy fixed =
        new ThreadRetryPolicy(
            3, ThreadRetryBackoffStrategy.FIXED, Duration.ofSeconds(2), Duration.ofSeconds(10));
    ThreadRetryPolicy exponential =
        new ThreadRetryPolicy(
            5,
            ThreadRetryBackoffStrategy.EXPONENTIAL,
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
            new ThreadRetryPolicy(
                -1,
                ThreadRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadRetryPolicy(
                1, ThreadRetryBackoffStrategy.EXPONENTIAL, Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadRetryPolicy(
                1,
                ThreadRetryBackoffStrategy.EXPONENTIAL,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class, () -> ThreadRetryPolicy.DEFAULT.delayBeforeRetry(0));
  }
}
