package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.ide.stream.IdeStreamTicketService;
import fun.fengwk.kkstudio.ide.stream.InMemoryIdeStreamTicketService;

import java.util.Map;

/**
 * IDE 网关数据面的 Web 装配：绑定 {@link IdeGatewayProperties}，提供进程内 stream ticket 服务、反向连接配对注册表， 以及代际关闭时的清理钩子。
 *
 * <p>无 cluster 配置时即单节点直连：本节点的 {@link IdeStreamTicketService}/{@link IdeStreamPairingRegistry}
 * 只服务本节点 建立的流。跨节点路由由 cluster 切片在 owner 定位层处理，本装配不做任何跨节点假设。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdeGatewayProperties.class)
public class IdeGatewayConfiguration {

  /**
   * Tomcat WS 客户端的握手 I/O 超时用户属性（毫秒）。
   *
   * <p>证据（tomcat-embed-websocket {@code 11.0.26}，{@code
   * WsWebSocketContainer.connectToServerRecursive} 字节码）： 客户端从 {@code
   * ClientEndpointConfig#getUserProperties()} 读取 {@code
   * org.apache.tomcat.websocket.IO_TIMEOUT_MS}（缺省 5000ms）作为握手期间 connect/write/read 的超时；Spring 侧由
   * {@code StandardWebSocketClient#setUserProperties} 写入同一 map（{@code StandardWebSocketClient}
   * 源码：{@code endpointConfig.getUserProperties().putAll(getUserProperties())}）。非 Tomcat
   * 容器忽略未知用户属性，因此这里安全。
   */
  static final String TOMCAT_HANDSHAKE_IO_TIMEOUT_MS =
      "org.apache.tomcat.websocket" + ".IO_TIMEOUT_MS";

  /**
   * 跨节点拨号共用的 WS 客户端：把握手 I/O 超时对齐到 {@code pairing-timeout}（默认 10s），使底层库自身也有与网关 deadline 一致的
   * 上限，而不是各库缺省（Tomcat 缺省 5s、且不同容器不一致）。
   */
  @Bean
  @ConditionalOnMissingBean
  public WebSocketClient ideWebSocketClient(IdeGatewayProperties properties) {
    StandardWebSocketClient client = new StandardWebSocketClient();
    client.setUserProperties(
        Map.of(
            TOMCAT_HANDSHAKE_IO_TIMEOUT_MS,
            String.valueOf(properties.requirePairingTimeout().toMillis())));
    return client;
  }

  @Bean
  @ConditionalOnMissingBean
  public IdeStreamTicketService ideStreamTicketService() {
    return new InMemoryIdeStreamTicketService();
  }

  @Bean
  public IdeStreamPairingRegistry<IdeStreamPeer> ideStreamPairingRegistry() {
    return new IdeStreamPairingRegistry<>();
  }

  /**
   * 代际关闭清理：关闭该 {@code (environment, lease)} 的全部反向流配对，并撤销 stream/bootstrap 票据与浏览器会话。
   *
   * <p>任一子步骤异常都不影响其它清理与会话状态推进，因此整体隔离。
   */
  @Bean
  public IdeLeaseCloseListener ideLeaseCloseListener(
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeStreamTicketService streamTickets,
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserSessionRegistry sessions) {
    return (environmentId, leaseToken) -> {
      closeQuietly(() -> pairings.closeLease(environmentId, leaseToken));
      closeQuietly(() -> streamTickets.revoke(environmentId, leaseToken));
      closeQuietly(() -> bootstrapTickets.revoke(environmentId, leaseToken));
      closeQuietly(() -> sessions.closeGeneration(environmentId, leaseToken));
    };
  }

  private static void closeQuietly(Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException ignored) {
      // 清理失败不得影响会话状态推进或其它清理步骤。
    }
  }
}
