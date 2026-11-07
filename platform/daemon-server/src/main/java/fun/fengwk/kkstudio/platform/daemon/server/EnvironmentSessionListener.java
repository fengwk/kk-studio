package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillStatePackage;

import java.util.List;

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

  /**
   * 当前 READY 连接上报了一轮完整的技能安装状态。
   *
   * <p>回调在会话锁外执行；上报是 Daemon 对本轮期望集合对账后的全部事实，宿主据此整体替换连接行上的 {@code skill_state} 投影。实现异常不影响会话状态。
   */
  default void onSkillState(EnvironmentId environmentId, List<DaemonSkillStatePackage> packages) {}
}
