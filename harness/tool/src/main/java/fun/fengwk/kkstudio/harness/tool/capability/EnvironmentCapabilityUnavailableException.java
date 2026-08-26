package fun.fengwk.kkstudio.harness.tool.capability;

/** Capability 发送前目标不可用；调用肯定未执行。 */
public final class EnvironmentCapabilityUnavailableException extends RuntimeException {

  public EnvironmentCapabilityUnavailableException(String message) {
    super(message);
  }

  public EnvironmentCapabilityUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
