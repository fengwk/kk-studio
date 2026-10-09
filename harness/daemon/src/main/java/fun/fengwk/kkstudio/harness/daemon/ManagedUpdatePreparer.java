package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;

/**
 * 受管更新准备端口：把一条受管更新命令准备为 handoff（或去敏失败）。
 *
 * <p>生产实现是 {@link ManagedDaemonUpdater}；窄接口让运行时可以在不触碰网络与文件系统的前提下验证「接受→准备→启动」编排。
 */
@FunctionalInterface
public interface ManagedUpdatePreparer {

  ManagedUpdateOutcome prepare(DaemonUpdateCommand command);
}
