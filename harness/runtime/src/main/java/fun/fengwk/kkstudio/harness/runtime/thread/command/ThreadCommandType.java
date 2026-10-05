package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** Thread mailbox 接受的 typed command 的最终集合（YOLO 走直接控制 API，不经过 mailbox）。 */
public enum ThreadCommandType {
  USER_MESSAGE,
  CUSTOM_MESSAGE,
  GOAL,
  SET_AGENT,
  SET_MODEL,
  SET_ENVIRONMENT,
  /** 系统通知：只由 Runtime 内部接受，普通 HTTP 客户端不允许提交。 */
  NOTIFICATION,
  /** 可信内部入口写入 Contributor branch state 的配置命令。 */
  SET_CONTRIBUTOR_STATE;

  /** 该 command 是否贡献一条会话消息（含 typed GOAL 产生的冻结 USER 消息）。 */
  public boolean isMessage() {
    return this == USER_MESSAGE || this == CUSTOM_MESSAGE || this == GOAL;
  }

  /** 该 command 是否只变更 branch 配置（不产生 message Entry，不单独唤醒模型）。 */
  public boolean isSetting() {
    return this == SET_AGENT
        || this == SET_MODEL
        || this == SET_ENVIRONMENT
        || this == SET_CONTRIBUTOR_STATE;
  }

  /** 该 command 是否为系统通知。 */
  public boolean isNotification() {
    return this == NOTIFICATION;
  }
}
