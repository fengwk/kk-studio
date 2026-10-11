package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个 Environment 当前 READY 连接的代际快照：Daemon 实例身份 + 当前路由租约代币。
 *
 * <p>IDE 入口 bootstrap、浏览器 cookie 与反向数据流都绑定该代际；租约失效/接管后旧代际的凭据一律作废并关闭，避免跨进程接管 后旧入口继续有效。
 */
public record IdeSessionIdentity(
    EnvironmentId environmentId, String daemonInstanceId, UUID leaseToken) {

  public IdeSessionIdentity {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    daemonInstanceId = Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
  }
}
