package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** Typed SET_YOLO payload; YOLO remains Thread policy rather than branch settings. */
public record SetYoloCommandPayload(boolean yoloEnabled) implements ThreadCommandPayload {

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_YOLO;
  }
}
