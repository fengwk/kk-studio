package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.StandardWebSocketUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/** 浏览器事件通道的 WebSocket 传输注册（{@code /api/events}）。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class ApplicationEventWebSocketConfiguration implements WebSocketConfigurer {

  private final ApplicationEventWebSocketHandler handler;

  public ApplicationEventWebSocketConfiguration(ApplicationEventWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, ApplicationEventWebSocketHandler.PATH)
        .setHandshakeHandler(new DefaultHandshakeHandler(new StandardWebSocketUpgradeStrategy()));
  }
}
