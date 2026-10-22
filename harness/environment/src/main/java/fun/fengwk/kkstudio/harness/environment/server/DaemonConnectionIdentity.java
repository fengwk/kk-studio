package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个 Environment 当前 READY 连接的代际快照：Daemon 实例身份 + 当前路由租约代币。
 *
 * <p>上层凭据（例如 IDE 入口 bootstrap、浏览器 cookie 与反向数据流）绑定该代际；租约失效或被接管后旧代际的凭据一律作废，避免跨进程接管后旧入口继续有效。
 */
public record DaemonConnectionIdentity(
    EnvironmentId environmentId, String daemonInstanceId, UUID leaseToken) {

  public DaemonConnectionIdentity {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    daemonInstanceId = Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
  }
}
