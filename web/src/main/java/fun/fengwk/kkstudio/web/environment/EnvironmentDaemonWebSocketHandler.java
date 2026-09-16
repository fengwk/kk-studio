package fun.fengwk.kkstudio.web.environment;

import jakarta.websocket.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.server.DaemonChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonEndpoint;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring WebSocket 适配器：把物理连接桥接为 {@link DaemonChannel} 并投递入站帧；本层不保存任何协议状态。
 *
 * <p><b>强制压缩：</b>连接建立后立即要求本次握手已协商 {@code permessage-deflate}；未协商成功时以 RFC 6455 close code 1010
 * 关闭连接且不向会话核心暴露通道，避免出现未经压缩的降级会话。
 */
@Component
public final class EnvironmentDaemonWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/harness/environment-daemon/v1";

  static final String PERMESSAGE_DEFLATE = "permessage-deflate";

  private final DaemonEndpoint endpoint;
  private final int maxMessageBytes;
  private final int queueCapacity;
  private final int maxBytes;
  private final int sendTimeoutMillis;
  private final Map<String, SpringWebSocketConnection> connections = new ConcurrentHashMap<>();

  public EnvironmentDaemonWebSocketHandler(
      DaemonEndpoint endpoint, EnvironmentDaemonTransportProperties transportProperties) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.maxMessageBytes =
        Objects.requireNonNull(transportProperties, "transportProperties").requireMaxMessageBytes();
    this.queueCapacity = transportProperties.requireQueueCapacity();
    this.maxBytes = transportProperties.requireMaxBytes();
    this.sendTimeoutMillis = transportProperties.requireSendTimeoutMillis();
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    if (!permessageDeflateNegotiated(session)) {
      rejectWithoutCompression(session);
      return;
    }
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

  /** 本次握手是否已协商必需的传输压缩扩展。 */
  private static boolean permessageDeflateNegotiated(WebSocketSession session) {
    List<WebSocketExtension> extensions = session.getExtensions();
    if (extensions == null) {
      return false;
    }
    for (WebSocketExtension extension : extensions) {
      if (PERMESSAGE_DEFLATE.equals(extension.getName().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /** 未协商必需扩展：以 1010 关闭且不向会话核心开放通道；关闭失败时连接已不可用，无需额外处理。 */
  private static void rejectWithoutCompression(WebSocketSession session) {
    try {
      session.close(CloseStatus.REQUIRED_EXTENSION);
    } catch (IOException ignored) {
      // 连接在关闭前已断开。
    }
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
      // 会话核心必须先解绑 registry/active/pending，再关闭 sender；二者都在 handler 锁外执行。
      endpoint.close(connection.connectionId());
    } finally {
      connection.close();
    }
  }

  private final class SpringWebSocketConnection implements DaemonChannel {

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
    public DaemonOfferResult offerText(String text) {
      return sender.offerText(text);
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
