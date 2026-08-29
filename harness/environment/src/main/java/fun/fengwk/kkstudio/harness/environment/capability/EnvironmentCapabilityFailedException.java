package fun.fengwk.kkstudio.harness.environment.capability;

/** Capability 以 FAILED 终态回报。 */
public final class EnvironmentCapabilityFailedException extends RuntimeException {

  public EnvironmentCapabilityFailedException(String message) {
    super(message);
  }

  public EnvironmentCapabilityFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
