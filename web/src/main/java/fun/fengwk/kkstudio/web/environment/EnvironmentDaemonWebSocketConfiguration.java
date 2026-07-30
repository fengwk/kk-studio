package fun.fengwk.kkstudio.web.environment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;

/**
 * WebSocket transport registration for the Daemon v1 Environment gateway.
 *
 * <p>Daemon {@code CAPABILITIES} frames routinely exceed Tomcat's default 8 KiB text buffer once
 * coding tools and skills are advertised. A tolerant {@link ServletServerContainerFactoryBean}
 * raises the JSR-356 {@link jakarta.websocket.server.ServerContainer} buffer limits when a real
 * servlet container is present and stays a no-op in non-container Spring contexts so MockMvc and
 * {@code WebEnvironment.MOCK} tests can boot.
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
   * Raise the JSR-356 text/binary frame buffer limits via Spring's standard factory bean. The
   * tolerant subclass detects missing {@code ServerContainer} attribute (non-container contexts)
   * and skips configuration instead of throwing.
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
