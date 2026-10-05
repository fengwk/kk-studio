package fun.fengwk.kkstudio.harness.runtime.retry;

import java.time.Duration;
import java.util.Objects;

/**
 * Model / Tool invocation 的共享自动重试判定：给定当前生效的 {@link InvocationRetryPolicy}、错误是否可重试，以及本次失败应计费的 已消耗
 * attempt，输出唯一的重试决策。
 *
 * <p>正常失败与租约失联恢复共用同一判定，避免恢复路径自建预算或重置计数：{@code chargeableAttempt} 必须已经包含本次失败所消耗的 attempt（RUNNING
 * 为已确认启动，DISPATCHING 为可能已发出的启动），因此 {@code allowsRetry} / {@code delayBeforeRetry}
 * 的语义与线上正常重试完全一致；一旦预算耗尽，调用方必须以明确的失败终止，不得无限重试。
 */
public record InvocationRetryDecision(boolean retry, Duration delay, int chargeableAttempt) {

  public InvocationRetryDecision {
    if (chargeableAttempt <= 0) {
      throw new IllegalArgumentException("chargeableAttempt must be positive");
    }
    if (retry) {
      delay = Objects.requireNonNull(delay, "delay");
    } else if (delay != null) {
      throw new IllegalArgumentException("a non-retry decision must not carry a delay");
    }
  }

  /** 依据策略与是否可重试错误判定：不可重试或预算不允许时返回明确的非重试决策，绝不静默降级为继续重试。 */
  public static InvocationRetryDecision decide(
      InvocationRetryPolicy policy, boolean retryable, int chargeableAttempt) {
    Objects.requireNonNull(policy, "policy");
    if (chargeableAttempt <= 0) {
      throw new IllegalArgumentException("chargeableAttempt must be positive");
    }
    if (!retryable || !policy.allowsRetry(chargeableAttempt)) {
      return new InvocationRetryDecision(false, null, chargeableAttempt);
    }
    return new InvocationRetryDecision(
        true, policy.delayBeforeRetry(chargeableAttempt), chargeableAttempt);
  }
}
