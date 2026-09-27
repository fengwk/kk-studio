package fun.fengwk.kkstudio.harness.environment.capability;

import java.time.Duration;

/** Capability 异步执行句柄，支持主动取消与带原因的收尾。 */
public interface EnvironmentCapabilityExecutionHandle {

  /** 尽最大努力请求取消正在进行的执行。 */
  void cancel();

  /** 查询句柄是否已被请求终止（取消或超时收尾）；协作式中断的能力应以它作为停止信号。 */
  boolean isCancelled();

  /**
   * 尽最大努力按明确原因请求收尾正在进行的执行。
   *
   * <p>默认实现等价于 {@link #cancel()}：既有能力不需要改动，也不会因此获得额外等待；无法区分原因的能力至少保证被终止。
   *
   * <p>覆盖本方法的能力应只在有界时间内完成收尾；需要运行时把终态提交让给能力时，同时覆盖 {@link #terminationGrace()}。
   */
  default void terminate(EnvironmentCapabilityTerminationCause cause) {
    cancel();
  }

  /**
   * {@link #terminate} 之后运行时最多等待能力自行提交终态的预算。
   *
   * <p>{@code ZERO}（默认）表示运行时立即以自己的终态收敛，不为该能力引入任何等待。返回正数表示能力保证在该预算内完成收尾并提交终态，
   * 使运行时能把终态提交让给它，从而携带已捕获输出；预算到期仍未收尾时运行时无条件以自己的终态收敛，因此超时与取消的收敛上界始终有界， 停机流程也不会依赖能力配合。
   */
  default Duration terminationGrace() {
    return Duration.ZERO;
  }
}
