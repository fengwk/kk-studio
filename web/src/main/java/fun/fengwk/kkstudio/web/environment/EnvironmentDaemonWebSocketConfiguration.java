package fun.fengwk.kkstudio.web.environment;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentGatewayProperties;

/**
 * WebSocket transport registration for the Daemon v1 Environment gateway.
 *
 * <p>Daemon {@code CAPABILITIES} frames routinely exceed Tomcat's default 8 KiB text buffer once
 * coding tools and skills are advertised. Both the Spring {@link ServletServerContainerFactoryBean}
 * and a late {@link ServletContextInitializer} raise the buffer so HELLO can complete into READY.
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

  /** Primary Spring Boot hook for the JSR-356 {@link ServerContainer} buffer limits. */
  @Bean
  public ServletServerContainerFactoryBean environmentDaemonWebSocketContainer(
      EnvironmentGatewayProperties properties) {
    int maxMessageBytes = properties.requireMaxMessageBytes();
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(maxMessageBytes);
    container.setMaxBinaryMessageBufferSize(maxMessageBytes);
    return container;
  }

  /**
   * Defense in depth: re-apply buffer limits after the servlet context is fully initialized.
   *
   * <p>Some container boot orders leave the default 8 KiB limits even when the factory bean is
   * present; re-asserting here prevents CAPABILITIES close code 1009.
   */
  @Bean
  public ServletContextInitializer environmentDaemonWebSocketBufferInitializer(
      EnvironmentGatewayProperties properties) {
    int maxMessageBytes = properties.requireMaxMessageBytes();
    return (ServletContext servletContext) -> {
      Object attribute = servletContext.getAttribute(ServerContainer.class.getName());
      if (attribute instanceof ServerContainer serverContainer) {
        serverContainer.setDefaultMaxTextMessageBufferSize(maxMessageBytes);
        serverContainer.setDefaultMaxBinaryMessageBufferSize(maxMessageBytes);
      }
    };
  }
}
