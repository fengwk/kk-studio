package fun.fengwk.kkstudio.harness.api.retry;

/** 自动重试两种退避算法。 */
public enum InvocationRetryBackoffStrategy {
  FIXED,
  EXPONENTIAL
}
