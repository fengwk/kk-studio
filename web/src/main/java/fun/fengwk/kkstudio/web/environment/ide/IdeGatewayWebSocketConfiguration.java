package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;

/**
 * Daemon 反向数据连接端点的 WebSocket 注册（{@code /api/harness/environment-stream/v1/{ticket}}）。
 *
 * <p>该端点位于网关自身 Origin 上，只接受携带一次性 ticket 的 Daemon 握手；浏览器 IDE Origin 的流量由 {@link
 * IdeOriginRequestFilter} 在更早处截获，不会到达这里。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class IdeGatewayWebSocketConfiguration implements WebSocketConfigurer {

  private final IdeReverseStreamWebSocketHandler handler;

  public IdeGatewayWebSocketConfiguration(
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeCommandTransport transport) {
    this.handler = new IdeReverseStreamWebSocketHandler(streamTickets, pairings, transport);
  }

  @Bean
  public IdeReverseStreamWebSocketHandler ideReverseStreamWebSocketHandler() {
    return handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, IdeGatewayProperties.STREAM_PATH + "/*");
  }
}
