package fun.fengwk.kkstudio.harness.environment.capability;

/** Capability 异步执行过程的监听回调。 */
public interface EnvironmentCapabilityExecutionListener {

  /** 收到阶段性流式输出。 */
  default void onPartial(EnvironmentCapabilityResult partial) {}

  /** 执行成功或业务失败回报。 */
  void onComplete(EnvironmentCapabilityResult result);

  /** 执行异常终止（非正常业务失败）。 */
  void onError(Throwable error);

  /**
   * 该次 invocation 的远端执行范围已经由正向证据确认结束。
   *
   * <p>这不是结果终态：不得从 {@link #onComplete}、{@link #onError}、超时或断连推断。绑定当前 invocation， 重复确认至多交付一次。
   */
  void onExitConfirmed();
}
