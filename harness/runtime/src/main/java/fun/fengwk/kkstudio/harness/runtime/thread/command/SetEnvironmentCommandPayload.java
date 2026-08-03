package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** Typed SET_ENVIRONMENT payload; null explicitly clears the Environment binding. */
public record SetEnvironmentCommandPayload(String environmentName) implements ThreadCommandPayload {

  public SetEnvironmentCommandPayload {
    environmentName =
        CommandValueValidation.nullableCanonicalName(environmentName, "environmentName");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ENVIRONMENT;
  }
}
