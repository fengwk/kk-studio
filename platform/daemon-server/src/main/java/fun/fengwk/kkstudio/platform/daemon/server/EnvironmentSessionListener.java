package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

/**
 * 会话事件窄端口：核心在状态推进后向宿主发出信号。
 *
 * <p>回调在核心状态锁外执行，实现异常不会影响会话状态。
 */
public interface EnvironmentSessionListener {

  /** 本地连接进入 READY。 */
  void onEnvironmentReady(EnvironmentId environmentId);

  /**
   * 当前 READY 连接的一次有效心跳已推进租约与运行状态。
   *
   * <p>回调在会话锁外执行；{@code ideLeaseRound} 是 Daemon 本轮发出的使用证明轮次，没有 IDE 视图时为 {@code null}。实现不得在该回调中阻塞
   * 心跳路径，也不得把心跳本身当作浏览器活跃证明。
   */
  default void onHeartbeat(
      EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {}
}
