package fun.fengwk.kkstudio.web.environment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;

/**
 * Daemon 端点 v2（WebSocket 路径 `/api/ai/environment/daemon/v2`）Environment gateway 的 WebSocket 传输注册。
 *
 * <p>当 Daemon 宣告 skills 时，{@code READY} 帧可能超过 Tomcat 默认的 8 KiB 文本缓冲区。存在真实 servlet container 时，宽容的
 * {@link ServletServerContainerFactoryBean} 会提高 JSR-356 {@link
 * jakarta.websocket.server.ServerContainer} 的缓冲区上限；在非容器 Spring 上下文中则保持 no-op，以便 MockMvc 与 {@code
 * WebEnvironment.MOCK} 测试可以启动。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class EnvironmentDaemonWebSocketConfiguration implements WebSocketConfigurer {

  private final EnvironmentDaemonWebSocketHandler handler;

  public EnvironmentDaemonWebSocketConfiguration(EnvironmentDaemonWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, EnvironmentDaemonWebSocketHandler.PATH);
  }

  /**
   * 通过 Spring 标准 factory bean 提高 JSR-356 文本/二进制帧缓冲区上限。宽容子类会检测缺失的 {@code ServerContainer}
   * 属性（非容器上下文）并跳过配置而不是抛异常。
   */
  @Bean
  public ServletServerContainerFactoryBean environmentDaemonWebSocketContainer(
      EnvironmentGatewayProperties properties) {
    int maxMessageBytes = properties.requireMaxMessageBytes();
    ServletServerContainerFactoryBean container =
        new EnvironmentDaemonWebSocketContainerFactoryBean();
    container.setMaxTextMessageBufferSize(maxMessageBytes);
    container.setMaxBinaryMessageBufferSize(maxMessageBytes);
    return container;
  }
}
