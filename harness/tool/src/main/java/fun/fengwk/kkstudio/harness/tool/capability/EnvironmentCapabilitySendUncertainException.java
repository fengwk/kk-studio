package fun.fengwk.kkstudio.harness.tool.capability;

/** Capability 发送结果不确定；调用可能已被接受，调用方不得重放。 */
public final class EnvironmentCapabilitySendUncertainException extends RuntimeException {

  public EnvironmentCapabilitySendUncertainException(String message) {
    super(message);
  }

  public EnvironmentCapabilitySendUncertainException(String message, Throwable cause) {
    super(message, cause);
  }
}
