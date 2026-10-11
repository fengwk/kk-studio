package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;

/**
 * 反向数据连接的 WebSocket 注册：Node 侧的受控 public stream endpoint 与节点间的内部单跳转发端点。
 *
 * <p>两个端点都在网关自身 Origin 上：{@code /api/harness/environment-stream/v1/{ticket}} 由 Daemon 握手消费一次性
 * ticket； {@code /internal/environment-ide/{env}/reverse?ticket=...} 只接受已通过 HMAC 认证的其它节点，并在任何握手前校验
 * owner 归属。浏览器 IDE Origin 的流量由 {@link IdeOriginRequestFilter} 在更早处截获，不会到达这里。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class IdeGatewayWebSocketConfiguration implements WebSocketConfigurer {

  private final IdeReverseStreamWebSocketHandler publicHandler;
  private final IdeInternalReverseWebSocketHandler internalHandler;
  private final InternalReverseHandshakeInterceptor internalHandshakeInterceptor;

  public IdeGatewayWebSocketConfiguration(
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeCommandTransport transport,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication clusterInternalAuthentication,
      IdeOwnerStreamForwarder ownerForwarder) {
    this.publicHandler =
        new IdeReverseStreamWebSocketHandler(
            streamTickets, pairings, transport, clusterNodeRegistry, ownerForwarder);
    this.internalHandler =
        new IdeInternalReverseWebSocketHandler(
            streamTickets, pairings, transport, clusterNodeRegistry);
    this.internalHandshakeInterceptor =
        new InternalReverseHandshakeInterceptor(clusterInternalAuthentication, clusterNodeRegistry);
  }

  @Bean
  public IdeReverseStreamWebSocketHandler ideReverseStreamWebSocketHandler() {
    return publicHandler;
  }

  @Bean
  public IdeInternalReverseWebSocketHandler ideInternalReverseWebSocketHandler() {
    return internalHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(publicHandler, IdeGatewayProperties.STREAM_PATH + "/*");
    registry
        .addHandler(internalHandler, ClusterInternalPaths.INTERNAL_IDE_PREFIX + "/*/reverse")
        .addInterceptors(internalHandshakeInterceptor);
  }
}
