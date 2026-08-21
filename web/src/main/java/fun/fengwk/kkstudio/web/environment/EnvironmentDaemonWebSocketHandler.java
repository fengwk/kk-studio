package fun.fengwk.kkstudio.web.environment;

import jakarta.websocket.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonEndpoint;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;

import java.io.IOException;
import java.util.Objects;

/** Spring WebSocket 适配器；持久协议语义仍封装在 {@link EnvironmentDaemonEndpoint} 中。 */
@Component
public final class EnvironmentDaemonWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/ai/environment/daemon/v2";

  private final EnvironmentDaemonEndpoint endpoint;
  private final int maxMessageBytes;

  public EnvironmentDaemonWebSocketHandler(
      EnvironmentDaemonEndpoint endpoint, EnvironmentGatewayProperties gatewayProperties) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.maxMessageBytes =
        Objects.requireNonNull(gatewayProperties, "gatewayProperties").requireMaxMessageBytes();
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    applyMessageBuffer(session);
    endpoint.open(new SpringWebSocketConnection(session));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    endpoint.receive(session.getId(), message.getPayload());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    endpoint.close(session.getId());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    endpoint.close(session.getId());
  }

  private void applyMessageBuffer(WebSocketSession session) {
    session.setTextMessageSizeLimit(maxMessageBytes);
    session.setBinaryMessageSizeLimit(maxMessageBytes);
    if (session instanceof NativeWebSocketSession nativeSession) {
      Session jsrSession = nativeSession.getNativeSession(Session.class);
      if (jsrSession != null) {
        jsrSession.setMaxTextMessageBufferSize(maxMessageBytes);
        jsrSession.setMaxBinaryMessageBufferSize(maxMessageBytes);
      }
    }
  }

  private static final class SpringWebSocketConnection implements EnvironmentDaemonConnection {

    private final WebSocketSession session;

    private SpringWebSocketConnection(WebSocketSession session) {
      this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public String connectionId() {
      return session.getId();
    }

    @Override
    public boolean isOpen() {
      return session.isOpen();
    }

    @Override
    public void sendText(String text) {
      try {
        session.sendMessage(new TextMessage(text));
      } catch (IOException error) {
        throw new IllegalStateException("cannot send daemon WebSocket message", error);
      }
    }

    @Override
    public void close() {
      try {
        session.close();
      } catch (IOException error) {
        throw new IllegalStateException("cannot close daemon WebSocket session", error);
      }
    }
  }
}
