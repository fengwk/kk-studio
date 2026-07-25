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
  CUSTOM_MESSAGE,
  /** 切换 Agent identity。 */
  SET_AGENT,
  /** 切换 Model/variant。 */
  SET_MODEL,
  /** 切换 Thread 的 YOLO policy。 */
  SET_YOLO;

  /** 是否为消息类输入（位于 TURN_BOUNDARY 末端）。 */
  public boolean isMessage() {
    return this == USER_MESSAGE || this == CUSTOM_MESSAGE;
  }

  /** 是否为配置类输入（位于 TURN_BOUNDARY 前段）。 */
  public boolean isConfig() {
    return this == SET_AGENT || this == SET_MODEL || this == SET_YOLO;
  }
}
