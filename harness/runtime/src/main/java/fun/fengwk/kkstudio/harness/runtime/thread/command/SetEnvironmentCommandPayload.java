package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

/** typed SET_ENVIRONMENT payload；null 显式清除 Environment route binding。 */
public record SetEnvironmentCommandPayload(EnvironmentId environmentId)
    implements ThreadCommandPayload {

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ENVIRONMENT;
  }
}
