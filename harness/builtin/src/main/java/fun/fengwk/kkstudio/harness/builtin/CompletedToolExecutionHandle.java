package fun.fengwk.kkstudio.harness.builtin;

import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;

/** 已终态完成、取消无副作用的执行句柄单例。 */
public enum CompletedToolExecutionHandle implements ToolExecutionHandle {
  INSTANCE;

  @Override
  public void cancel() {}

  @Override
  public boolean isCancelled() {
    return false;
  }
}
