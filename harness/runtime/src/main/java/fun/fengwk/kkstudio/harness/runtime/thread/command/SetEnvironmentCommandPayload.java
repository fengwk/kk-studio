package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;

/** typed SET_ENVIRONMENT payload；null 显式清除完整 Environment binding。 */
public record SetEnvironmentCommandPayload(EnvironmentBinding environment)
    implements ThreadCommandPayload {

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ENVIRONMENT;
  }
}
