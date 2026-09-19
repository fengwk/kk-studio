package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** Thread mailbox 接受的 typed command 的最终集合（YOLO 走直接控制 API，不经过 mailbox）。 */
public enum ThreadCommandType {
  USER_MESSAGE,
  CUSTOM_MESSAGE,
  SET_AGENT,
  SET_MODEL,
  SET_ENVIRONMENT;

  /** 该 command 是否贡献一条会话消息。 */
  public boolean isMessage() {
    return this == USER_MESSAGE || this == CUSTOM_MESSAGE;
  }

  /** 该 command 是否只变更 branch settings（不产生 message Entry）。 */
  public boolean isSetting() {
    return this == SET_AGENT || this == SET_MODEL || this == SET_ENVIRONMENT;
  }
}
