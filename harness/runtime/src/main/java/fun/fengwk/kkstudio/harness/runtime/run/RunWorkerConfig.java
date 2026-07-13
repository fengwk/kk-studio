package fun.fengwk.kkstudio.harness.runtime.run;

import java.time.Duration;
import java.util.Objects;

/** Turn worker 的持久恢复与批处理参数。 */
public record RunWorkerConfig(
    Duration leaseDuration,
    Duration deltaFlushInterval,
    int deltaBatchBytes,
    int maxAttempts,
    Duration retryBaseDelay) {

  public static final RunWorkerConfig DEFAULT =
      new RunWorkerConfig(
          Duration.ofSeconds(30), Duration.ofMillis(150), 8 * 1024, 3, Duration.ofSeconds(1));

  public RunWorkerConfig {
    leaseDuration = positive(leaseDuration, "leaseDuration");
    deltaFlushInterval = positive(deltaFlushInterval, "deltaFlushInterval");
    retryBaseDelay = positive(retryBaseDelay, "retryBaseDelay");
    if (deltaFlushInterval.compareTo(Duration.ofMillis(100)) < 0
        || deltaFlushInterval.compareTo(Duration.ofMillis(250)) > 0) {
      throw new IllegalArgumentException("deltaFlushInterval must be between 100ms and 250ms");
    }
    if (deltaBatchBytes < 8 * 1024 || deltaBatchBytes > 16 * 1024) {
      throw new IllegalArgumentException("deltaBatchBytes must be between 8KiB and 16KiB");
    }
    if (maxAttempts <= 0) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
  }

  public Duration backoffForAttempt(int attempt) {
    int exponent = Math.max(0, Math.min(attempt - 1, 30));
    long multiplier = 1L << exponent;
    try {
      return retryBaseDelay.multipliedBy(multiplier);
    } catch (ArithmeticException ignored) {
      return Duration.ofMillis(Long.MAX_VALUE);
    }
  }

  private static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
