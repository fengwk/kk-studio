package fun.fengwk.kkstudio.harness.environment.capability;

/** 向 daemon 发送执行请求时发生不确定错误（例如连接已断开）。 */
public final class EnvironmentCapabilitySendUncertainException extends RuntimeException {

  public EnvironmentCapabilitySendUncertainException(String message) {
    super(message);
  }

  public EnvironmentCapabilitySendUncertainException(String message, Throwable cause) {
    super(message, cause);
  }
}
