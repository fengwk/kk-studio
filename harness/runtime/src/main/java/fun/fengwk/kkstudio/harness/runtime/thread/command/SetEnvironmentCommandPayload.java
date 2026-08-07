package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

/** typed SET_ENVIRONMENT payload；null 显式清除 Environment route binding。 */
public record SetEnvironmentCommandPayload(EnvironmentName environmentName)
    implements ThreadCommandPayload {

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ENVIRONMENT;
  }
}
