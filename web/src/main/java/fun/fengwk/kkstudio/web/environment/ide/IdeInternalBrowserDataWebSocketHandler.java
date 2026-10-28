package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** owner 内部浏览器逻辑 WS 端点：HMAC/owner/查询参数已由握手拦截器校验。 */
final class IdeInternalBrowserDataWebSocketHandler extends AbstractWebSocketHandler {

  private final IdeBrowserWebSocketGateway gateway;
  private volatile IdeBrowserDataSessionHandler delegate;

  IdeInternalBrowserDataWebSocketHandler(IdeBrowserWebSocketGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
  }

  @Override
  public boolean supportsPartialMessages() {
    return true;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    EnvironmentId environmentId =
        (EnvironmentId) session.getAttributes().get(IdeDataHandshakeInterceptor.ENVIRONMENT);
    UUID viewId = (UUID) session.getAttributes().get(IdeDataHandshakeInterceptor.VIEW_ID);
    String rawPathAndQuery =
        (String) session.getAttributes().get(IdeDataHandshakeInterceptor.RAW_PATH);
    if (environmentId == null || viewId == null || rawPathAndQuery == null) {
      closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("ide data route is invalid"));
      return;
    }
    List<MuxHeader> headers =
        IdeDataHeaderPolicy.forwardHandshakeHeaders(session.getHandshakeHeaders(), true);
    delegate =
        gateway.handleInternalUpgrade(
            session,
            environmentId,
            viewId,
            rawPathAndQuery,
            session.getHandshakeHeaders().getFirst("Cookie"),
            headers);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    IdeBrowserDataSessionHandler current = delegate;
    if (current != null) {
      try {
        current.handleTextMessage(session, message);
      } catch (Exception error) {
        closeQuietly(session, CloseStatus.SERVER_ERROR);
      }
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    IdeBrowserDataSessionHandler current = delegate;
    if (current != null) {
      try {
        current.handleBinaryMessage(session, message);
      } catch (Exception error) {
        closeQuietly(session, CloseStatus.SERVER_ERROR);
      }
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    IdeBrowserDataSessionHandler current = delegate;
    if (current != null) {
      current.afterConnectionClosed(session, status);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    IdeBrowserDataSessionHandler current = delegate;
    if (current != null) {
      current.handleTransportError(session, exception);
    }
  }

  private static void closeQuietly(WebSocketSession session, CloseStatus status) {
    synchronized (session) {
      if (!session.isOpen()) {
        return;
      }
      try {
        session.close(status);
      } catch (Exception ignored) {
        // 已断开。
      }
    }
  }
}
