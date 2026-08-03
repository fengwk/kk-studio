package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

/** Typed SET_ENVIRONMENT payload; null explicitly clears the Environment route binding. */
public record SetEnvironmentCommandPayload(EnvironmentId environmentId)
    implements ThreadCommandPayload {

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ENVIRONMENT;
  }
}
