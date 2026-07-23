package fun.fengwk.kkstudio.harness.runtime.retry;

/** 在每次 Invocation 瞬态失败时读取当前生效的自动重试策略。 */
@FunctionalInterface
public interface InvocationRetryPolicyResolver {
  InvocationRetryPolicy resolve();
}
