package fun.fengwk.kkstudio.harness.environment.capability;

import java.time.Duration;

/** Capability 异步执行句柄，支持协作取消与放弃等待时的资源清理。 */
public interface EnvironmentCapabilityExecutionHandle {

  /** 协作式取消意图：请求能力停止执行，但不清理运行时在途资源，也不保证副作用未发生。 */
  void cancel();

  /** 查询句柄是否已被请求终止（取消或超时收尾）；协作式中断的能力应以它作为停止信号。 */
  boolean isCancelled();

  /**
   * 调用方放弃等待，清理本地在途与传输资源，并尽最大努力向远端发送带明确原因的取消请求。
   *
   * <p>本方法不等待终态，也不会额外等待 {@link #terminalDeliveryGrace()}；远端清理是 best effort，不保证副作用未发生或被回滚。
   *
   * @param cause 终止原因，不得为 {@code null}
   */
  void terminate(EnvironmentCapabilityTerminationCause cause);

  /**
   * 远端终态传输恢复窗。
   *
   * <p>运行时在 {@code invoke} 成功后只读取一次，并与执行预算合成为单一等待上界 {@code timeout + terminalDeliveryGrace}。该窗口不表示
   * {@link #terminate} 之后还会等待，也不会被 partial 或重连重置、延长。计时从 invoke 前开始；timeout 为零时显式无界。返回 {@link
   * Duration#ZERO} 表示不引入恢复窗；返回 {@code null} 或负数属于契约违规，运行时显式失败。
   */
  Duration terminalDeliveryGrace();
}
