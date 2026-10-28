package fun.fengwk.kkstudio.harness.environment.server;

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
   * 另一个 Daemon 实例在本节点接管了该 Environment：{@code previous} 代际就此结束。
   *
   * <p>同一实例断线重连不触发本回调，它的进程、code-server 与视图都还在。宿主据此作废并关闭绑定旧代际的 IDE 入口凭据与反向数据流（ticket
   * 撤销只是其中一环，已建立的流也必须关闭）。默认空实现，不强制所有宿主关心 IDE 数据面。
   */
  default void onDaemonInstanceReplaced(DaemonConnectionIdentity previous) {}

  /**
   * 当前 READY 连接的一次有效心跳已推进租约与运行状态。
   *
   * <p>回调在会话锁外执行；{@code ideLeaseRound} 是 Daemon 本轮发出的使用证明轮次，没有 IDE 视图时为 {@code null}。实现不得在该回调中阻塞
   * 心跳路径，也不得把心跳本身当作浏览器活跃证明。
   */
  default void onHeartbeat(
      EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {}
}
