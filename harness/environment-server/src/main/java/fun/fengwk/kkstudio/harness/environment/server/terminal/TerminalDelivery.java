package fun.fengwk.kkstudio.harness.environment.server.terminal;

import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 shell 事件投递：把权威 owner 节点、READY 租约围栏、Daemon 实例身份与终端响应组合。
 *
 * <p>{@code ownerNodeId} 是承载该 Daemon 连接的 App 节点，{@code leaseToken} 是服务端认证的 READY 租约， {@code
 * response} 是复用 {@code TerminalControlCodec} 的既有控制响应对象。{@code ownerNodeId}/{@code
 * leaseToken}/{@code response} 不可空，且 {@link #toString()} 不回显控制 payload。
 *
 * <p>{@code daemonInstanceId} 允许为 {@code null} 的唯一场景是 owner 侧在真正递交 Daemon 之前就确定「未执行」：响应必须是 ERROR
 * 事件、 disposition 为 {@link ErrorDisposition#NOT_EXECUTED} 且事件不携带终端 identity，表示当前没有已认证的 Daemon。真实
 * Daemon 回传的事件仍必须提供 真实 UUID。该 null 不更新或替代任何已确认的 daemon/terminal/stream 绑定。
 *
 * @param ownerNodeId 承载连接的 App 节点
 * @param leaseToken 服务端 READY 时冻结的租约 token
 * @param daemonInstanceId 认证的 Daemon 实例身份，仅在「无认证 Daemon 的确定未执行错误」时为 {@code null}
 * @param response 终端控制响应
 */
public record TerminalDelivery(
    UUID ownerNodeId, UUID leaseToken, UUID daemonInstanceId, TerminalResponse response) {

  public TerminalDelivery {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(response, "response");
    if (daemonInstanceId == null && !isUnauthenticatedNotExecuted(response)) {
      throw new IllegalArgumentException(
          "daemonInstanceId may be null only for a NOT_EXECUTED error without terminal identity");
    }
  }

  /** 该投递是否表示「没有当前已认证 Daemon」的确定未执行错误。 */
  public boolean unauthenticatedNotExecuted() {
    return daemonInstanceId == null;
  }

  private static boolean isUnauthenticatedNotExecuted(TerminalResponse response) {
    TerminalEvent event = response.event();
    return event.identity() == null
        && event.payload() instanceof TerminalEvent.ErrorPayload error
        && error.disposition() == ErrorDisposition.NOT_EXECUTED;
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
