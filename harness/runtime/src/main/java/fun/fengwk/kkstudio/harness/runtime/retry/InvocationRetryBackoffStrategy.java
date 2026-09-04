package fun.fengwk.kkstudio.harness.runtime.retry;

/** 自动重试两种退避算法。 */
public enum InvocationRetryBackoffStrategy {
  FIXED,
  EXPONENTIAL;

  public static InvocationRetryBackoffStrategy fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("retry backoff strategy must be FIXED or EXPONENTIAL");
    }
    return switch (value) {
      case "FIXED" -> FIXED;
      case "EXPONENTIAL" -> EXPONENTIAL;
      default -> throw new IllegalArgumentException(
          "retry backoff strategy must be FIXED or EXPONENTIAL");
    };
  }
}
