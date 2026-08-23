package fun.fengwk.kkstudio.platform.ai.environment.registry;

/** 内存 registry 观察到的 Live Environment 连接生命周期。 */
public enum LiveEnvironmentStatus {
  /** HELLO 已接受；READY 尚未完成。 */
  CONNECTING,
  /** 已 READY，可进行 Environment tool 派发与 skill 加载。 */
  READY
}
