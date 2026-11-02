package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.ExecutionSelection;

import java.util.Objects;

/** typed SET_EXECUTION payload；执行选择（Driver 标识符与设置）作为单一值整体变更。 */
public record SetExecutionCommandPayload(ExecutionSelection execution)
    implements ThreadCommandPayload {

  public SetExecutionCommandPayload {
    execution = Objects.requireNonNull(execution, "execution");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_EXECUTION;
  }
}
