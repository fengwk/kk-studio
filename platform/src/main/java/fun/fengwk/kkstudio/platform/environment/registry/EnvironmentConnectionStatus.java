package fun.fengwk.kkstudio.platform.environment.registry;

/** 内存/数据库 registry 观察到的 Environment 连接生命周期状态。 */
public enum EnvironmentConnectionStatus {
  /** HELLO 已接受；READY 尚未完成。 */
  CONNECTING,
  /** 已 READY，可进行 Environment capability 派发与 skill 加载。 */
  READY
}
