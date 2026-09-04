package fun.fengwk.kkstudio.platform.environment.registry;

/** 内存/数据库 registry 观察到的 Live Environment 连接生命周期状态。 */
public enum LiveEnvironmentStatus {
  /** HELLO 已接受，READY 尚未完成。 */
  CONNECTING,

  /** 已完成就绪握手，可进行能力派发与技能加载。 */
  READY
}
