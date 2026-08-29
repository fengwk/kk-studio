package fun.fengwk.kkstudio.harness.environment.capability;

/** Capability 异步执行句柄，支持主动取消。 */
public interface EnvironmentCapabilityExecutionHandle {

  /** 尽最大努力请求取消正在进行的执行。 */
  void cancel();

  /** 查询句柄是否已发起取消请求。 */
  boolean isCancelled();
}
