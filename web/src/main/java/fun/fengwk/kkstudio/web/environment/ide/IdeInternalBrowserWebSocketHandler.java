package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * owner 节点接收其它节点单跳转发的浏览器 WS 数据端点（{@code /internal/environment-ide/{env}/ws?path=...}）。
 *
 * <p>入站连接必须已通过 {@link IdeInternalBrowserHandshakeInterceptor} 的 HMAC 认证、owner 归属与浏览器会话/代际校验（全部在握手
 * 之前完成）。连接建立后本端点即用与浏览器直连完全相同的 {@link IdeBrowserStreamSession} 状态机：按原始 path 发 {@code OPEN_STREAM}、
 * 配对 Daemon 反向连接、按 credit 与 FIN 双向透传。因此跨节点只多一跳，分段、credit 与 ACK 语义不变。
 */
@Slf4j
public final class IdeInternalBrowserWebSocketHandler extends AbstractWebSocketHandler {

  /** 握手属性：已认证的目标 Environment。 */
  static final String ATTRIBUTE_ENVIRONMENT = "kk-studio.ide.internal.browser.environment";

  /** 握手属性：已校验的浏览器会话。 */
  static final String ATTRIBUTE_SESSION = "kk-studio.ide.internal.browser.session";

  /** 握手属性：转发方还原的浏览器原始 path（含 query）。 */
  static final String ATTRIBUTE_PATH = "kk-studio.ide.internal.browser.path";

  private final IdeStreamDeps deps;
  private final Map<String, IdeBrowserStreamSession> sessions = new ConcurrentHashMap<>();

  public IdeInternalBrowserWebSocketHandler(IdeStreamDeps deps) {
    this.deps = Objects.requireNonNull(deps, "deps");
  }

  @Override
  public boolean supportsPartialMessages() {
    return true;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    EnvironmentId environmentId = attribute(session, ATTRIBUTE_ENVIRONMENT, EnvironmentId.class);
    IdeBrowserSessionRegistry.Session browserSession =
        attribute(session, ATTRIBUTE_SESSION, IdeBrowserSessionRegistry.Session.class);
    String rawPathAndQuery = attribute(session, ATTRIBUTE_PATH, String.class);
    if (environmentId == null || browserSession == null || rawPathAndQuery == null) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    session.setTextMessageSizeLimit(IdeBrowserStreamSession.SEGMENT_BYTES);
    session.setBinaryMessageSizeLimit(IdeBrowserStreamSession.SEGMENT_BYTES);
    IdeBrowserStreamSession stream =
        new IdeBrowserStreamSession(
            session,
            environmentId,
            browserSession,
            rawPathAndQuery,
            List.of(),
            deps,
            () -> sessions.remove(session.getId()));
    sessions.put(session.getId(), stream);
    stream.start();
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    offer(
        session,
        new IdeBrowserStreamSession.Frame(
            true, message.getPayload().getBytes(StandardCharsets.UTF_8), message.isLast()));
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    ByteBuffer payload = message.getPayload();
    byte[] bytes = new byte[payload.remaining()];
    payload.get(bytes);
    offer(session, new IdeBrowserStreamSession.Frame(false, bytes, message.isLast()));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    detach(session);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    log.debug(
        "internal browser transport error on {}: {}", session.getId(), exception.getMessage());
    detach(session);
  }

  private void offer(WebSocketSession session, IdeBrowserStreamSession.Frame frame) {
    IdeBrowserStreamSession stream = sessions.get(session.getId());
    if (stream != null && !stream.offer(frame)) {
      // 有界入站预算/关闭：fail-closed，绝不无界缓冲。
      detach(session);
      try {
        session.close(CloseStatus.TOO_BIG_TO_PROCESS);
      } catch (Exception ignored) {
        // 会话已断开。
      }
    }
  }

  private void detach(WebSocketSession session) {
    IdeBrowserStreamSession stream = sessions.remove(session.getId());
    if (stream != null) {
      stream.onBrowserDetached();
    }
  }

  private static <T> T attribute(WebSocketSession session, String name, Class<T> type) {
    Object value = session.getAttributes().get(name);
    return type.isInstance(value) ? type.cast(value) : null;
  }
}
