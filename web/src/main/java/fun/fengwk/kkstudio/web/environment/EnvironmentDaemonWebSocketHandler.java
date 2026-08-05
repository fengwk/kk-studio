package fun.fengwk.kkstudio.web.environment;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonEndpoint;

import java.io.IOException;
import java.util.Objects;

/**
 * Spring WebSocket adapter; durable protocol semantics remain behind {@link
 * EnvironmentDaemonEndpoint}.
 */
@Component
public final class EnvironmentDaemonWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/ai/environment/daemon/v2";

  private final EnvironmentDaemonEndpoint endpoint;

  public EnvironmentDaemonWebSocketHandler(EnvironmentDaemonEndpoint endpoint) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
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
