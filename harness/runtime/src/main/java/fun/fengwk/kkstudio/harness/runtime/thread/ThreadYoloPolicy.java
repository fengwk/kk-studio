package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Objects;
import java.util.UUID;

/**
 * Thread 的持久 YOLO 策略：根线程维护自己的开关，子代理跟随真实执行根。
 *
 * <p>{@link ThreadYoloMode#FOLLOW} 必须携带不可变的 {@code rootThreadId}（真实执行根），非 FOLLOW 模式不得携带目标。根与子代理的
 * 一致性由 {@link ThreadState} 构造与 {@link ThreadState#validateTransition} 共同保证：Follow
 * 目标创建后不可变，根开关只修改根自身的 {@code ENABLE}/{@code DISABLE}。
 */
public record ThreadYoloPolicy(ThreadYoloMode mode, UUID rootThreadId) {

  public ThreadYoloPolicy {
    Objects.requireNonNull(mode, "mode");
    if (mode == ThreadYoloMode.FOLLOW) {
      Objects.requireNonNull(rootThreadId, "rootThreadId");
    } else if (rootThreadId != null) {
      throw new IllegalArgumentException("non-follow yolo policy must not carry a root thread");
    }
  }

  /** 根的独立开关：{@code ENABLE} / {@code DISABLE}，不携带 Follow 目标。 */
  public static ThreadYoloPolicy root(boolean enabled) {
    return new ThreadYoloPolicy(enabled ? ThreadYoloMode.ENABLE : ThreadYoloMode.DISABLE, null);
  }

  /** 子代理跟随不可变执行根。 */
  public static ThreadYoloPolicy follow(UUID rootThreadId) {
    return new ThreadYoloPolicy(
        ThreadYoloMode.FOLLOW, Objects.requireNonNull(rootThreadId, "rootThreadId"));
  }

  /** 是否为跟随执行根的子代理策略。 */
  public boolean isFollow() {
    return mode == ThreadYoloMode.FOLLOW;
  }

  /** 根策略当前是否开启；仅对 {@link ThreadYoloMode#ENABLE} 为 true，FOLLOW 不是 effective 事实。 */
  public boolean isEnabled() {
    return mode == ThreadYoloMode.ENABLE;
  }
}
