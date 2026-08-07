package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** typed SET_YOLO payload；YOLO 仍是 Thread policy 而非 branch settings。 */
public record SetYoloCommandPayload(boolean yoloEnabled) implements ThreadCommandPayload {

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_YOLO;
  }
}
