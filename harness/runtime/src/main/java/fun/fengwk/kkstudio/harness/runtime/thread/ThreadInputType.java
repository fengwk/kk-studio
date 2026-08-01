package fun.fengwk.kkstudio.harness.runtime.thread;

/**
 * Thread mailbox 的命令类型枚举。
 *
 * <p>固定枚举值集合，不为未来命令预留。
 */
public enum ThreadInputType {
  /** 用户消息。 */
  USER_MESSAGE,
  /** 业务扩展注入的消息。 */
  CUSTOM_MESSAGE;

  /** 是否为消息类输入。 */
  public boolean isMessage() {
    return this == USER_MESSAGE || this == CUSTOM_MESSAGE;
  }
}
