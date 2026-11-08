package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.Optional;

/**
 * 本节点当前 READY Daemon 实例身份的权威查询。
 *
 * <p>授权与路由登记在自己的登记锁内调用它复核身份：迟到的旧实例结果即使已通过调用方的锁外检查，也会在写入前被判为过期，因此不能清理或覆盖新代际的登记。
 * 没有就绪连接时返回空，登记方按不可用处理，不做任何写入。
 */
@FunctionalInterface
public interface IdeCurrentGeneration {

  /** 该 Environment 当前 READY 连接背后的 Daemon 实例；无本地连接或未 READY 时为空。 */
  Optional<DaemonConnectionIdentity> current(EnvironmentId environmentId);
}
