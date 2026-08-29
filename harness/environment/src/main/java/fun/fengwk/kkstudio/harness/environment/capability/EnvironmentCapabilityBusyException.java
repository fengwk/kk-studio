package fun.fengwk.kkstudio.harness.environment.capability;

/** 目标 Environment 正在执行互斥独占操作，暂时无法接收新的 Capability 调用。 */
public class EnvironmentCapabilityBusyException extends RuntimeException {

  public EnvironmentCapabilityBusyException(String message) {
    super(message);
  }

  public EnvironmentCapabilityBusyException(String message, Throwable cause) {
    super(message, cause);
  }
}
