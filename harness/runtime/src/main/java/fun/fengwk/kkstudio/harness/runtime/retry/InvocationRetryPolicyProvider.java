package fun.fengwk.kkstudio.harness.runtime.retry;

/** 向 Model/Tool processor 提供每次 retry 判定现读的 {@link InvocationRetryPolicy}。 */
@FunctionalInterface
public interface InvocationRetryPolicyProvider {

  /** 返回当前生效的 invocation retry 策略；每次 retry 判定点调用。 */
  InvocationRetryPolicy retryPolicy();
}
