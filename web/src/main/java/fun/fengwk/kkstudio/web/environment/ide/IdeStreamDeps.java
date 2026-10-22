package fun.fengwk.kkstudio.web.environment.ide;

import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.ide.stream.IdeStreamTicketService;

import java.util.Objects;

/**
 * 一条 IDE 浏览器 WS 数据流所需的最小依赖集：命令传输、一次性 stream ticket、反向配对注册表与 gateway 配置。
 *
 * <p>真实浏览器入口与内部单跳端点共用同一状态机，因此依赖显式传递而不是隐式共享宿主字段。
 */
record IdeStreamDeps(
    IdeCommandTransport transport,
    IdeStreamTicketService streamTickets,
    IdeStreamPairingRegistry<IdeStreamPeer> pairings,
    IdeGatewayProperties properties) {

  IdeStreamDeps {
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(streamTickets, "streamTickets");
    Objects.requireNonNull(pairings, "pairings");
    Objects.requireNonNull(properties, "properties");
  }
}
