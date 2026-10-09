package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.Objects;

/**
 * 一次控制请求的 wire 包装：仅把服务端 route 与 {@link TerminalCommand} 组合，不携带 PG lease、owner 地址或 Daemon 注册凭据。
 *
 * @param route 真实浏览器连接来源
 * @param command 控制命令
 */
public record TerminalRequest(TerminalRoute route, TerminalCommand command) {

  public TerminalRequest {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(command, "command");
  }
}
