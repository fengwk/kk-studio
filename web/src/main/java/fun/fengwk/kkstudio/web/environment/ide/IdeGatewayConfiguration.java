package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * IDE 数据面的 Web 装配：复用数据连接注册表、原生 WS 客户端与数据连接心跳调度器。
 *
 * <p>每个 Environment 只在当前 READY 控制身份下维持一条已接受的数据连接；代际结束时关闭该连接、撤销浏览器会话与入口票据。这里不注册任何 per-exchange
 * ticket/pairing 设施。
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

  /** 跨节点拨号共用的 WS 客户端：握手 I/O 超时与 DATA_BIND 回连等待保持一致。 */
  @Bean
  @ConditionalOnMissingBean
  public WebSocketClient ideWebSocketClient(IdeGatewayProperties properties) {
    StandardWebSocketClient client = new StandardWebSocketClient();
    client.setUserProperties(
        Map.of(
            TOMCAT_HANDSHAKE_IO_TIMEOUT_MS,
            String.valueOf(properties.requireDataConnectTimeout().toMillis())));
    return client;
  }

  @Bean
  @ConditionalOnMissingBean
  public IdeDataConnectionRegistry ideDataConnectionRegistry(
      EnvironmentDaemonServer server, IdeGatewayProperties properties) {
    return new IdeDataConnectionRegistry(server, properties::dataStreamUrl);
  }

  @Bean(name = "ideDataHeartbeatScheduler", destroyMethod = "shutdownNow")
  public ScheduledExecutorService ideDataHeartbeatScheduler() {
    return Executors.newScheduledThreadPool(
        2,
        runnable -> {
          Thread thread = new Thread(runnable, "ide-data-heartbeat");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * 代际结束清理：关闭当前数据连接、撤销入口票据与旧浏览器会话。
   *
   * <p>任一子步骤异常都不影响其它清理与会话状态推进，因此整体隔离。
   */
  @Bean
  public IdeGenerationCloseListener ideGenerationCloseListener(
      IdeDataConnectionRegistry dataConnections,
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserSessionRegistry sessions) {
    return generation -> {
      closeQuietly(() -> dataConnections.closeGeneration(generation));
      closeQuietly(() -> bootstrapTickets.revoke(generation));
      closeQuietly(() -> sessions.closeGeneration(generation));
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
