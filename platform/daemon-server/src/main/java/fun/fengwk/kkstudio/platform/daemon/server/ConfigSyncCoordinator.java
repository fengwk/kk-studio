package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Environment 配置同步的单飞状态机。
 *
 * <p>state 的创建、dirty 标记、执行权取得与结束移除都在 {@link ConcurrentHashMap#compute} / {@link
 * ConcurrentHashMap#computeIfPresent} 的同一 key 原子边界内完成；数据库查询始终在边界外执行。查询期间的 新通知只标记 dirty，由同一 worker
 * 再读一次，不创建第二个 state 或并发任务。
 */
final class ConfigSyncCoordinator {

  enum Phase {
    IDLE,
    SCHEDULED,
    RUNNING
  }

  /** 仅由 {@link #states} 的 compute 回调读写，保证同一 Environment 的状态转移原子。 */
  static final class State {
    private Phase phase = Phase.IDLE;
    private boolean dirty;

    Phase phase() {
      return phase;
    }

    boolean dirty() {
      return dirty;
    }
  }

  private final ConcurrentHashMap<EnvironmentId, State> states = new ConcurrentHashMap<>();

  /** 标记 dirty；无执行者时把 state 置为 SCHEDULED 并返回，调用方负责提交任务。 */
  State schedule(EnvironmentId environmentId) {
    State[] scheduled = new State[1];
    states.compute(
        environmentId,
        (ignored, existing) -> {
          State state = existing == null ? new State() : existing;
          state.dirty = true;
          if (state.phase == Phase.IDLE) {
            state.phase = Phase.SCHEDULED;
            scheduled[0] = state;
          }
          return state;
        });
    return scheduled[0];
  }

  /** 执行前取得执行权并消费上一轮 dirty；state 已被移除或替换时返回 false。 */
  boolean begin(EnvironmentId environmentId, State state) {
    boolean[] begun = new boolean[1];
    states.computeIfPresent(
        environmentId,
        (ignored, current) -> {
          if (current != state || state.phase == Phase.IDLE) {
            return current;
          }
          state.phase = Phase.RUNNING;
          state.dirty = false;
          begun[0] = true;
          return current;
        });
    return begun[0];
  }

  /** 查询后消费新 dirty；clean 时在同一 compute 中移除 state，避免旧 worker 与新 state 并存。 */
  boolean repeat(EnvironmentId environmentId, State state) {
    boolean[] repeat = new boolean[1];
    states.computeIfPresent(
        environmentId,
        (ignored, current) -> {
          if (current != state) {
            return current;
          }
          if (state.dirty) {
            state.dirty = false;
            repeat[0] = true;
            return current;
          }
          state.phase = Phase.IDLE;
          return null;
        });
    return repeat[0];
  }

  /** 执行器拒绝提交时原子回到 IDLE；dirty 保留给下一次通知或心跳重试。 */
  void rejected(EnvironmentId environmentId, State state) {
    states.computeIfPresent(
        environmentId,
        (ignored, current) -> {
          if (current != state) {
            return current;
          }
          state.phase = Phase.IDLE;
          return current;
        });
  }

  int size() {
    return states.size();
  }
}
