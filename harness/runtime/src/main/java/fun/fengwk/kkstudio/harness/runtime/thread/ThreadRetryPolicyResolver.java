package fun.fengwk.kkstudio.harness.runtime.thread;

/** 在每次瞬态失败时读取当前生效的自动重试策略。 */
@FunctionalInterface
public interface ThreadRetryPolicyResolver {
  ThreadRetryPolicy resolve();
}
