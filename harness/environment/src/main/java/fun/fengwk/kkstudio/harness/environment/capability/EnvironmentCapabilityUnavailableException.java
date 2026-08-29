package fun.fengwk.kkstudio.harness.environment.capability;

/** 目标 Environment 不可用（例如没有 live daemon 连接）。 */
public class EnvironmentCapabilityUnavailableException extends RuntimeException {

  public EnvironmentCapabilityUnavailableException(String message) {
    super(message);
  }

  public EnvironmentCapabilityUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
