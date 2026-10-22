package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.UUID;

/**
 * 会话事件窄端口：核心在状态推进后向宿主发出信号。
 *
 * <p>回调在核心状态锁外执行，实现异常不会影响会话状态。
 */
public interface EnvironmentSessionListener {

  /** 本地连接进入 READY。 */
  void onEnvironmentReady(EnvironmentId environmentId);

  /**
   * 一个连接代际被关闭（断线/接管/换代）：该 {@code (environmentId, leaseToken)} 的路由租约不再由本节点持有。
   *
   * <p>宿主据此作废并关闭绑定该代际的 IDE 入口凭据与反向数据流（ticket 撤销只是其中一环，已建立的流也必须关闭）。默认空实现， 不强制所有宿主关心 IDE 数据面。
   */
  default void onEnvironmentDisconnected(EnvironmentId environmentId, UUID leaseToken) {}
}
