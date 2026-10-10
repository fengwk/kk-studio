package fun.fengwk.kkstudio.harness.environment.server.terminal;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个 Environment 当前权威 READY 路由的不可变快照：承载连接的 App 节点与 READY 时冻结的 PG 租约。
 *
 * <p>它只描述「谁是 owner」这一事实，不含终端/流/writer 身份，也不构成任何准入授权：owner 收到控制请求后仍须在其既有 READY/PG 租约准入路径上
 * 重新核验。两个字段都不可空，且 {@link #toString()} 不回显 lease token。
 *
 * @param ownerNodeId 当前持有未过期 READY 租约的 App 节点
 * @param leaseToken 该 READY 绑定的 PG 租约 token
 */
public record EnvironmentTerminalRoute(UUID ownerNodeId, UUID leaseToken) {

  public EnvironmentTerminalRoute {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
  }

  @Override
  public String toString() {
    return "EnvironmentTerminalRoute[ownerNodeId=" + ownerNodeId + ", leaseToken=<redacted>]";
  }
}
