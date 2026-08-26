package fun.fengwk.kkstudio.harness.tool.capability;

/** 一次 Environment Capability 执行的取消句柄。 */
public interface EnvironmentCapabilityExecutionHandle {

  /** 请求取消；调用必须幂等。 */
  void cancel();

  /** 是否已经请求取消。 */
  boolean isCancelled();
}
