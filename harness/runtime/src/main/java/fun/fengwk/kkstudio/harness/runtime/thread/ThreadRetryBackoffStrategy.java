package fun.fengwk.kkstudio.harness.runtime.thread;

/** 自动重试两种退避算法。 */
public enum ThreadRetryBackoffStrategy {
  FIXED,
  EXPONENTIAL;

  public static ThreadRetryBackoffStrategy fromValue(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("retry backoff strategy must not be blank");
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unsupported retry backoff strategy: " + value, error);
    }
  }
}
