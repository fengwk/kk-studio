package fun.fengwk.kkstudio.harness.tool.capability;

/** Capability 以 FAILED 终态回报。 */
public final class EnvironmentCapabilityFailedException extends RuntimeException {

  public EnvironmentCapabilityFailedException(String message) {
    super(
        message == null || message.isBlank()
            ? "Environment capability execution failed."
            : message);
  }

  public EnvironmentCapabilityFailedException(String message, Throwable cause) {
    super(
        message == null || message.isBlank() ? "Environment capability execution failed." : message,
        cause);
  }
}
