package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** Final set of typed commands accepted by the Thread mailbox. */
public enum ThreadCommandType {
  USER_MESSAGE,
  CUSTOM_MESSAGE,
  SET_AGENT,
  SET_MODEL,
  SET_THINKING_LEVEL,
  SET_ACTIVE_TOOLS,
  SET_YOLO,
  SET_ENVIRONMENT;

  /** Whether this command contributes a conversation message. */
  public boolean isMessage() {
    return this == USER_MESSAGE || this == CUSTOM_MESSAGE;
  }
}
