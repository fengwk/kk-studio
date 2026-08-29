package fun.fengwk.kkstudio.harness.environment.capability;

/** Capability 以 CANCELLED 终态回报。 */
public final class EnvironmentCapabilityCancelledException extends RuntimeException {

  public EnvironmentCapabilityCancelledException(String message) {
    super(
        message == null || message.isBlank()
            ? "Environment capability execution cancelled."
            : message);
  }

  public EnvironmentCapabilityCancelledException(String message, Throwable cause) {
    super(
        message == null || message.isBlank()
            ? "Environment capability execution cancelled."
            : message,
        cause);
  }
}
