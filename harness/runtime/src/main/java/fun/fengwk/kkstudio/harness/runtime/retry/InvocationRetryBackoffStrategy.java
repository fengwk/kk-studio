package fun.fengwk.kkstudio.harness.runtime.retry;

import java.util.Locale;

/** 自动重试两种退避算法。 */
public enum InvocationRetryBackoffStrategy {
  FIXED,
  EXPONENTIAL;

  public static InvocationRetryBackoffStrategy fromValue(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("retry backoff strategy must not be blank");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unsupported retry backoff strategy: " + value, error);
    }
  }
}
