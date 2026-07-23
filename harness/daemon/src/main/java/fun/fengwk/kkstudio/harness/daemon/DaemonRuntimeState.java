package fun.fengwk.kkstudio.harness.daemon;

/** Daemon 到 Platform Gateway 的当前连接生命周期状态。 */
public enum DaemonRuntimeState {
  STOPPED,
  CONNECTING,
  READY,
  DISCONNECTED
}
