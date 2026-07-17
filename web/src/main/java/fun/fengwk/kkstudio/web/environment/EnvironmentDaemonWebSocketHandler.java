package fun.fengwk.kkstudio.web.environment;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonGateway;
import java.io.IOException;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Spring WebSocket adapter; durable protocol semantics remain in {@link EnvironmentDaemonGateway}.
 */
@Component
public final class EnvironmentDaemonWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/environments/daemon/v1";

  private final EnvironmentDaemonGateway gateway;

  public EnvironmentDaemonWebSocketHandler(EnvironmentDaemonGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    gateway.open(new SpringWebSocketConnection(session));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    gateway.receive(session.getId(), message.getPayload());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    gateway.close(session.getId());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    gateway.close(session.getId());
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
