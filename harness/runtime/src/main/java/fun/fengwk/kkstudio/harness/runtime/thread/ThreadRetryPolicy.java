package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Duration;
import java.util.Objects;

/** Provider 瞬态失败的全局自动重试策略。 */
public record ThreadRetryPolicy(
    int maxRetries,
    ThreadRetryBackoffStrategy backoffStrategy,
    Duration baseDelay,
    Duration maxDelay) {
  public static final ThreadRetryPolicy DEFAULT =
      new ThreadRetryPolicy(
          3, ThreadRetryBackoffStrategy.EXPONENTIAL, Duration.ofSeconds(2), Duration.ofSeconds(60));

  public ThreadRetryPolicy {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must not be negative");
    }
    backoffStrategy = Objects.requireNonNull(backoffStrategy, "backoffStrategy");
    baseDelay = requirePositive(baseDelay, "baseDelay");
    maxDelay = requirePositive(maxDelay, "maxDelay");
    if (maxDelay.compareTo(baseDelay) < 0) {
      throw new IllegalArgumentException("maxDelay must not be less than baseDelay");
    }
  }

  /** {@code retryAttempt} 从 1 开始，表示首次失败后的第一次重试。 */
  public Duration delayBeforeRetry(int retryAttempt) {
    if (retryAttempt <= 0) {
      throw new IllegalArgumentException("retryAttempt must be positive");
    }
    if (backoffStrategy == ThreadRetryBackoffStrategy.FIXED || retryAttempt == 1) {
      return baseDelay;
    }
    long ceilingMillis = maxDelay.toMillis();
    long delayMillis = baseDelay.toMillis();
    for (int attempt = 1; attempt < retryAttempt && delayMillis < ceilingMillis; attempt++) {
      delayMillis = Math.min(ceilingMillis, saturatedDouble(delayMillis));
    }
    return Duration.ofMillis(delayMillis);
  }

  public boolean allowsRetry(int retryAttempt) {
    return retryAttempt > 0 && retryAttempt <= maxRetries;
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
      throw new IllegalArgumentException(name + " must be at least one millisecond");
    }
    return value;
  }

  private static long saturatedDouble(long value) {
    return value > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : value * 2;
  }
}
