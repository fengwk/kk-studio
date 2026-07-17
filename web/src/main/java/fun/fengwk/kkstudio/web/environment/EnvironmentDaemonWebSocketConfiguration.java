package fun.fengwk.kkstudio.web.environment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentGatewayProperties;

/** WebSocket transport registration for the Daemon v1 Environment gateway. */
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

  /** Raises the servlet container text buffer for bounded Base64 artifact callback payloads. */
  @Bean
  @ConditionalOnBean(ServletWebServerFactory.class)
  @ConditionalOnMissingBean(ServletServerContainerFactoryBean.class)
  public ServletServerContainerFactoryBean environmentDaemonWebSocketContainer(
      EnvironmentGatewayProperties properties) {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(properties.requireMaxMessageBytes());
    return container;
  }
}
