package fun.fengwk.kkstudio.harness.tool.capability;

/** Capability 发送前发生瞬时容量冲突；调用肯定未执行。 */
public final class EnvironmentCapabilityBusyException extends RuntimeException {

  public EnvironmentCapabilityBusyException(String message) {
    super(message);
  }

  public EnvironmentCapabilityBusyException(String message, Throwable cause) {
    super(message, cause);
  }
}
