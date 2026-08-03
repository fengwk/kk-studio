package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.util.Objects;

/** Typed SET_MODEL payload; ModelRef and variant are changed as one value. */
public record SetModelCommandPayload(ModelSelection model) implements ThreadCommandPayload {

  public SetModelCommandPayload {
    model = Objects.requireNonNull(model, "model");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_MODEL;
  }
}
