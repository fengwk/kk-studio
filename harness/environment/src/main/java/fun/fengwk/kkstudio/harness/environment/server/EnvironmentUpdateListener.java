package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;

/**
 * 受管更新回执窄端口：核心把 Daemon 上报的阶段回执转交宿主消费。
 *
 * <p>回调在核心状态锁外执行，实现异常不会影响会话状态。回执只表达 Daemon 侧准备进度，不是最终成功事实。
 */
public interface EnvironmentUpdateListener {

  /** 收到某 Environment 的一次更新阶段回执。 */
  void onUpdateResult(EnvironmentId environmentId, DaemonUpdateResult result);
}
