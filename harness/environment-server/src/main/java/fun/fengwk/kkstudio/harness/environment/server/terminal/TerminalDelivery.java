package fun.fengwk.kkstudio.harness.environment.server.terminal;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次由 Daemon 回传的 shell 事件投递：把权威 owner 节点、READY 租约围栏、Daemon 实例身份与终端响应组合。
 *
 * <p>{@code ownerNodeId} 是承载该 Daemon 连接的 App 节点，{@code leaseToken}/{@code daemonInstanceId} 是服务端认证的
 * READY 绑定，{@code response} 是复用 {@code TerminalControlCodec} 的既有控制响应对象。四个字段都不可空，且 {@link
 * #toString()} 不回显控制 payload。
 *
 * @param ownerNodeId 承载连接的 App 节点
 * @param leaseToken 服务端 READY 时冻结的租约 token
 * @param daemonInstanceId 认证的 Daemon 实例身份
 * @param response 终端控制响应
 */
public record TerminalDelivery(
    UUID ownerNodeId, UUID leaseToken, UUID daemonInstanceId, TerminalResponse response) {

  public TerminalDelivery {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    Objects.requireNonNull(response, "response");
  }

  @Override
  public String toString() {
    return "TerminalDelivery[ownerNodeId="
        + ownerNodeId
        + ", leaseToken=<redacted>, daemonInstanceId="
        + daemonInstanceId
        + ", response=<redacted>]";
  }
}
