package fun.fengwk.kkstudio.harness.daemon;

/** Daemon 到 Platform Gateway 的当前连接生命周期状态。 */
public enum DaemonRuntimeState {
  STOPPED,
  CONNECTING,
  READY,
  DISCONNECTED,

  /** 终态失败：HELLO 声称的注册身份已被另一个 live daemon 持有；进程应停止重连并非零退出。 */
  FAILED
}
