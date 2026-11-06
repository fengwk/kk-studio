package fun.fengwk.kkstudio.platform.environment.dto;

/**
 * 受管更新命令的接收状态。
 *
 * <p>{@code SENT_UNCONFIRMED} 只表示命令已进入当前控制连接的发送队列，不表示 Daemon 已接受或更新已经开始；浏览器必须继续读取 runtime
 * 快照，不能据此宣称启动成功。
 */
public enum EnvironmentUpdateCommandStatus {
  /** 当前已经是目标版本，未下发命令。 */
  CURRENT,

  /** 当前快照已显示同一目标正在更新，未重复下发命令。 */
  IN_PROGRESS,

  /** 命令已进入控制连接发送队列；Daemon 接收结果未知。 */
  SENT_UNCONFIRMED
}
