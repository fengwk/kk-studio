package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.util.Objects;

/** typed SET_MODEL payload；ModelRef 与 variant 作为单一值整体变更。 */
public record SetModelCommandPayload(ModelSelection model) implements ThreadCommandPayload {

  public SetModelCommandPayload {
    model = Objects.requireNonNull(model, "model");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_MODEL;
  }
}
