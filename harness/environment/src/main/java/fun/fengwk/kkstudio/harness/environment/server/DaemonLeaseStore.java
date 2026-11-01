package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;

import java.time.Duration;
import java.util.UUID;

/**
 * Environment 路由租约窄端口。
 *
 * <p>实现负责持久化围栏语义（{@code environment_id, owner_node_id, lease_token}），核心只按返回值推进会话状态；实现可以抛出运行时异常表达
 * 基础设施不可用，核心会 fail-closed 处理。
 */
public interface DaemonLeaseStore {

  /** 握手准入时原子校验注册凭据并抢占或续约路由租约。 */
  LeaseBindResult tryAcquire(
      EnvironmentId environmentId, String registrationToken, Duration leaseDuration);

  /** 将环境标记为 READY 并写入宿主 metadata；围栏失效返回 false。 */
  boolean markReady(
      EnvironmentId environmentId,
      UUID leaseToken,
      DaemonCapabilities capabilities,
      Duration leaseDuration);

  /** 续约路由租约；围栏失效返回 false。 */
  boolean heartbeat(EnvironmentId environmentId, UUID leaseToken, Duration leaseDuration);

  /** 连接断开时回退为 CONNECTING 并保留重连宽限租约。 */
  boolean disconnect(EnvironmentId environmentId, UUID leaseToken, Duration graceDuration);

  /** 持有者主动放弃路由租约，围栏校验通过后清除持有者与租约，使任何节点都可立即抢占；围栏失效返回 false。 */
  boolean release(EnvironmentId environmentId, UUID leaseToken);

  /** 判定当前节点是否持有该 {@code leaseToken} 的有效 READY 租约（本地 INVOKE 准入围栏）。 */
  boolean holdsReadyLease(EnvironmentId environmentId, UUID leaseToken);

  /** 判定当前节点是否仍持有该活跃连接代币（同节点重复准入保护）。 */
  boolean hasActiveLeaseToken(EnvironmentId environmentId, UUID leaseToken);
}
