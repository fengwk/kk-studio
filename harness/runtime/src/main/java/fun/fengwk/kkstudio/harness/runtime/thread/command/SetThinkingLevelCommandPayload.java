package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** SET_THINKING_LEVEL 的 typed payload。 */
public record SetThinkingLevelCommandPayload(String thinkingLevel) implements ThreadCommandPayload {

  public SetThinkingLevelCommandPayload {
    thinkingLevel = CommandValueValidation.requireCanonicalName(thinkingLevel, "thinkingLevel");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_THINKING_LEVEL;
  }
}
