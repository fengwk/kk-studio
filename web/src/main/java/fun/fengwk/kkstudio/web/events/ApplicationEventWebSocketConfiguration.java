package fun.fengwk.kkstudio.web.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** 浏览器事件通道的 WebSocket 传输注册（{@code /ws/events}）。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class ApplicationEventWebSocketConfiguration implements WebSocketConfigurer {

  private final ApplicationEventWebSocketHandler handler;
  private final HandshakeInterceptor interceptor;

  public ApplicationEventWebSocketConfiguration(
      ApplicationEventWebSocketHandler handler, HandshakeInterceptor interceptor) {
    this.handler = handler;
    this.interceptor = interceptor;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, ApplicationEventWebSocketHandler.PATH)
        .addInterceptors(interceptor);
  }
}
