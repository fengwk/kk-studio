package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;

/** 持久 ToolInvocation 的执行目标类型。 */
public enum ToolTargetType {
  CONTROL,
  CLOUD,
  ENVIRONMENT;

  public static ToolTargetType fromExecutionMode(ToolExecutionMode mode) {
    return valueOf(mode.name());
  }
}
