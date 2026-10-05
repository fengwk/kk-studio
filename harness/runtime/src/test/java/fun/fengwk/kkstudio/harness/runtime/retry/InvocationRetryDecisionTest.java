package fun.fengwk.kkstudio.harness.runtime.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** 正常失败与租约失联恢复共用的 retry 判定契约：已消耗 attempt 必须包含本次失败，预算允许才返回带策略 delay 的重试决策，否则明确不重试。 */
class InvocationRetryDecisionTest {

  private static final InvocationRetryPolicy RETRY_ONCE =
      new InvocationRetryPolicy(
          1, InvocationRetryBackoffStrategy.FIXED, Duration.ofMillis(400), Duration.ofMillis(400));

  @Test
  void retryableFailureWithinBudgetCarriesPolicyDelay() {
    InvocationRetryDecision decision = InvocationRetryDecision.decide(RETRY_ONCE, true, 1);

    assertTrue(decision.retry());
    assertEquals(Duration.ofMillis(400), decision.delay());
    assertEquals(1, decision.chargeableAttempt());
  }

  @Test
  void retryableFailureBeyondBudgetDoesNotRetry() {
    InvocationRetryDecision decision = InvocationRetryDecision.decide(RETRY_ONCE, true, 2);

    assertFalse(decision.retry());
    assertNull(decision.delay());
  }

  @Test
  void nonRetryableFailureDoesNotRetryEvenWithinBudget() {
    InvocationRetryDecision decision = InvocationRetryDecision.decide(RETRY_ONCE, false, 1);

    assertFalse(decision.retry());
    assertNull(decision.delay());
  }

  @Test
  void rejectsInconsistentDecisions() {
    assertThrows(IllegalArgumentException.class, () -> new InvocationRetryDecision(false, null, 0));
    assertThrows(
        IllegalArgumentException.class, () -> InvocationRetryDecision.decide(RETRY_ONCE, true, 0));
    assertThrows(NullPointerException.class, () -> new InvocationRetryDecision(true, null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InvocationRetryDecision(false, Duration.ofSeconds(1), 1));
  }
}
