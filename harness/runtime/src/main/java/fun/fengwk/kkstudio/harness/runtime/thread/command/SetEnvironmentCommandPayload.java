package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;

/**
 * typed SET_ENVIRONMENT payload；null 显式清除默认分支 workspace path。
 *
 * <p>非 null 值在构造边界按 {@link EnvironmentWorkspacePath} 校验 canonical 相对 wire 路径形状， 与 BranchSettings 的
 * durable 持久化契约一致。
 */
public record SetEnvironmentCommandPayload(String workspacePath) implements ThreadCommandPayload {

  public SetEnvironmentCommandPayload {
    if (workspacePath != null) {
      workspacePath = EnvironmentWorkspacePath.requireCanonicalRelativePath(workspacePath);
    }
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ENVIRONMENT;
  }
}
