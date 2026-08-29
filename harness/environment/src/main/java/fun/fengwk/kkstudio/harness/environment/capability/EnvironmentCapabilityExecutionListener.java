package fun.fengwk.kkstudio.harness.environment.capability;

/** Capability 异步执行过程的监听回调。 */
public interface EnvironmentCapabilityExecutionListener {

  /** 收到阶段性流式输出。 */
  default void onPartial(EnvironmentCapabilityResult partial) {}

  /** 执行成功或业务失败回报。 */
  void onComplete(EnvironmentCapabilityResult result);

  /** 执行异常终止（非正常业务失败）。 */
  void onError(Throwable error);
}
