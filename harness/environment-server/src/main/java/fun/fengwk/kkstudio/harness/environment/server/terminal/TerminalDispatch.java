package fun.fengwk.kkstudio.harness.environment.server.terminal;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次定向到某个 Environment owner 的 shell 控制投递：把权威 PG READY lease 围栏与终端控制请求组合。
 *
 * <p>{@code leaseToken} 是服务端在 READY 时冻结的路由租约，只有当前仍持有该租约的连接才能执行；{@code request} 是复用 {@code
 * TerminalControlCodec} 的既有控制请求对象（含服务端路由与命令）。二者都不可空，且 {@link #toString()} 不回显控制 payload 或 token
 * secret。
 *
 * @param leaseToken 服务端 READY 时冻结的租约 token
 * @param request 终端控制请求
 */
public record TerminalDispatch(UUID leaseToken, TerminalRequest request) {

  public TerminalDispatch {
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(request, "request");
  }

  @Override
  public String toString() {
    return "TerminalDispatch[leaseToken=<redacted>, request=<redacted>]";
  }
}
