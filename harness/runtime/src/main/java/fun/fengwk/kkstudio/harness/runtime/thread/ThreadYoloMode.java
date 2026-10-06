package fun.fengwk.kkstudio.harness.runtime.thread;

/**
 * Thread 的持久 YOLO 策略模式。
 *
 * <p>执行根（{@code parentThreadId} 为 null）只能 {@link #ENABLE} 或 {@link #DISABLE}，其开关是根的独立事实；子代理只能是
 * {@link #FOLLOW}，直接跟随真实执行根，不能维护自己的开关，也不能跟随中间父节点。
 */
public enum ThreadYoloMode {
  /** 根 Thread 开启 YOLO：普通工具调用跳过权限预检。 */
  ENABLE,
  /** 根 Thread 关闭 YOLO：普通工具调用走权限预检。 */
  DISABLE,
  /** 子代理跟随执行根的实际开关；不携带独立的 effective 值。 */
  FOLLOW
}
