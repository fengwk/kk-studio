package fun.fengwk.kkstudio.platform.environment.dto;

/** Environment 当前连接状态；租约或心跳窗口过期的连接由服务端派生为 {@link #OFFLINE}。 */
public enum EnvironmentStatus {
  CONNECTING,
  READY,
  OFFLINE
}
