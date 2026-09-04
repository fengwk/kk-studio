package fun.fengwk.kkstudio.harness.daemon;

/** Daemon 到 Platform Gateway 的当前连接生命周期状态。 */
public enum DaemonRuntimeState {
  /** Daemon 连接未启动或已主动停止。 */
  STOPPED,

  /** 正在尝试向 Gateway 建立连接。 */
  CONNECTING,

  /** 握手就绪，具备双向通信与能力派发条件。 */
  READY,

  /** 连接断开，等待触发断线重连。 */
  DISCONNECTED,

  /** Gateway 拒绝环境注册后停止重连并非零退出的终态。 */
  FAILED
}
