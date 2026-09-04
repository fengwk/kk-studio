package fun.fengwk.kkstudio.harness.environment.capability;

/** 向 Daemon 发送 Capability 执行请求时发生不确定错误（调用可能已被接受，调用方不得自动重放）。 */
public final class EnvironmentCapabilitySendUncertainException extends RuntimeException {

  public EnvironmentCapabilitySendUncertainException(String message) {
    super(message);
  }

  public EnvironmentCapabilitySendUncertainException(String message, Throwable cause) {
    super(message, cause);
  }
}
