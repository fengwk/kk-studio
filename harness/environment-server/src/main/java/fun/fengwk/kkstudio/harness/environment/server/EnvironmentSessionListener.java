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
}
