package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.Objects;

/**
 * 一次控制响应的 wire 包装：仅把服务端 route 与 {@link TerminalEvent} 组合，不携带 PG lease、owner 地址或 Daemon 注册凭据。
 *
 * @param route 真实浏览器连接收件人
 * @param event 控制事件
 */
public record TerminalResponse(TerminalRoute route, TerminalEvent event) {

  public TerminalResponse {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(event, "event");
  }
}
