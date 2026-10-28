package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;

import java.util.concurrent.ScheduledExecutorService;

/**
 * 数据 WebSocket 端点注册：Daemon 物理连接、节点间物理连接单跳桥与节点间浏览器逻辑 WS。
 *
 * <p>物理端点只消费一次性 DATA_BIND ticket；内部端点全部由 HMAC 与 owner 归属拦截器先行校验。浏览器 WS 的对外入口由 {@link
 * IdeGatewayFilter} 在 servlet 层截获，不经过这里。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class IdeGatewayWebSocketConfiguration implements WebSocketConfigurer {

  private final IdeDataPhysicalWebSocketHandler publicDataHandler;
  private final IdeDataPhysicalWebSocketHandler internalDataHandler;
  private final IdeInternalBrowserDataWebSocketHandler internalBrowserHandler;
  private final IdeDataHandshakeInterceptor internalDataInterceptor;
  private final IdeDataHandshakeInterceptor internalBrowserInterceptor;

  public IdeGatewayWebSocketConfiguration(
      IdeDataConnectionRegistry dataConnections,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication clusterInternalAuthentication,
      IdeClusterClient clusterInternalClient,
      IdeBrowserWebSocketGateway browserGateway,
      WebSocketClient webSocketClient,
      IdeGatewayProperties properties,
      @Qualifier("ideDataHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
    this.publicDataHandler =
        new IdeDataPhysicalWebSocketHandler(
            dataConnections,
            clusterNodeRegistry,
            clusterInternalClient,
            webSocketClient,
            heartbeatScheduler,
            properties,
            false);
    this.internalDataHandler =
        new IdeDataPhysicalWebSocketHandler(
            dataConnections,
            clusterNodeRegistry,
            clusterInternalClient,
            webSocketClient,
            heartbeatScheduler,
            properties,
            true);
    this.internalBrowserHandler = new IdeInternalBrowserDataWebSocketHandler(browserGateway);
    this.internalDataInterceptor =
        new IdeDataHandshakeInterceptor(clusterInternalAuthentication, clusterNodeRegistry, false);
    this.internalBrowserInterceptor =
        new IdeDataHandshakeInterceptor(clusterInternalAuthentication, clusterNodeRegistry, true);
  }

  @Bean
  public IdeDataPhysicalWebSocketHandler ideDataPhysicalWebSocketHandler() {
    return publicDataHandler;
  }

  @Bean
  public IdeDataPhysicalWebSocketHandler ideInternalDataWebSocketHandler() {
    return internalDataHandler;
  }

  @Bean
  public IdeInternalBrowserDataWebSocketHandler ideInternalBrowserDataWebSocketHandler() {
    return internalBrowserHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(publicDataHandler, IdeGatewayProperties.DATA_PATH + "/*");
    registry
        .addHandler(internalDataHandler, IdeDataInternalPaths.PHYSICAL_DATA_MAPPING)
        .addInterceptors(internalDataInterceptor);
    registry
        .addHandler(internalBrowserHandler, IdeDataInternalPaths.BROWSER_DATA_WS_MAPPING)
        .addInterceptors(internalBrowserInterceptor);
  }
}
