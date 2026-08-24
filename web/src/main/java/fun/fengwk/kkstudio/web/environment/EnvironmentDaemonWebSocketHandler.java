package fun.fengwk.kkstudio.web.environment;

import jakarta.websocket.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonEndpoint;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentGatewayProperties;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Spring WebSocket 适配器；持久协议语义仍封装在 {@link EnvironmentDaemonEndpoint} 中。 */
@Component
public final class EnvironmentDaemonWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/ai/environment/daemon/v2";

  private final EnvironmentDaemonEndpoint endpoint;
  private final int maxMessageBytes;
  private final int queueCapacity;
  private final int maxBytes;
  private final int sendTimeoutMillis;
  private final Map<String, SpringWebSocketConnection> connections = new ConcurrentHashMap<>();

  public EnvironmentDaemonWebSocketHandler(
      EnvironmentDaemonEndpoint endpoint, EnvironmentGatewayProperties gatewayProperties) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.maxMessageBytes =
        Objects.requireNonNull(gatewayProperties, "gatewayProperties").requireMaxMessageBytes();
    this.queueCapacity = gatewayProperties.requireQueueCapacity();
    this.maxBytes = gatewayProperties.requireMaxBytes();
    this.sendTimeoutMillis = gatewayProperties.requireSendTimeoutMillis();
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    applyMessageBuffer(session);
    SpringWebSocketConnection connection = new SpringWebSocketConnection(session);
    connections.put(session.getId(), connection);
    try {
      endpoint.open(connection);
    } catch (RuntimeException error) {
      connections.remove(session.getId(), connection);
      connection.close();
      throw error;
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    SpringWebSocketConnection connection = connections.get(session.getId());
    if (connection != null && connection.matches(session)) {
      endpoint.receive(session.getId(), message.getPayload());
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    disconnect(session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    disconnect(session);
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

  private void disconnect(WebSocketSession session) {
    SpringWebSocketConnection connection = connections.get(session.getId());
    if (connection != null && connection.matches(session)) {
      disconnect(connection);
    }
  }

  private void disconnect(SpringWebSocketConnection connection) {
    if (!connections.remove(connection.connectionId(), connection)) {
      return;
    }
    try {
      // Gateway 必须先解绑 registry/active/pending，再关闭 sender；二者都在 handler 锁外执行。
      endpoint.close(connection.connectionId());
    } finally {
      connection.close();
    }
  }

  private final class SpringWebSocketConnection implements EnvironmentDaemonConnection {

    private final WebSocketSession session;
    private final DaemonOutboundSender sender;

    private SpringWebSocketConnection(WebSocketSession session) {
      this.session = Objects.requireNonNull(session, "session");
      this.sender =
          new DaemonOutboundSender(
              session, queueCapacity, maxBytes, sendTimeoutMillis, error -> disconnect(this));
    }

    @Override
    public String connectionId() {
      return session.getId();
    }

    @Override
    public boolean isOpen() {
      return sender.isOpen();
    }

    @Override
    public boolean sendText(String text) {
      return sender.enqueue(text);
    }

    @Override
    public void closeAfterFlush() {
      sender.closeAfterFlush();
    }

    @Override
    public void close() {
      sender.close();
    }

    private boolean matches(WebSocketSession candidate) {
      return session == candidate;
    }
  }
}
