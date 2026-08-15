package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 浏览器事件通道的 WebSocket 传输注册（{@code /api/events/v1}）。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class ApplicationEventWebSocketConfiguration implements WebSocketConfigurer {

  private final ApplicationEventWebSocketHandler handler;

  public ApplicationEventWebSocketConfiguration(ApplicationEventWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, ApplicationEventWebSocketHandler.PATH);
  }
}
