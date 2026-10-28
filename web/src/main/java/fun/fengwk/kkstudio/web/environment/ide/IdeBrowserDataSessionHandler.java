package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 浏览器逻辑 WebSocket 一侧的容器回调：只做有界入队与连接生命周期收敛。 */
final class IdeBrowserDataSessionHandler extends AbstractWebSocketHandler {

  private final EnvironmentId environmentId;
  private final UUID viewId;
  private final long bindingEpoch;
  private final String rawPathAndQuery;
  private final List<MuxHeader> headers;
  private final DaemonConnectionIdentity controlIdentity;
  private final IdeDataConnectionRegistry registry;
  private volatile IdeBrowserDataStream stream;

  IdeBrowserDataSessionHandler(
      EnvironmentId environmentId,
      UUID viewId,
      long bindingEpoch,
      String rawPathAndQuery,
      List<MuxHeader> headers,
      DaemonConnectionIdentity controlIdentity,
      IdeDataConnectionRegistry registry) {
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.viewId = Objects.requireNonNull(viewId, "viewId");
    this.bindingEpoch = bindingEpoch;
    this.rawPathAndQuery = Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    this.headers = List.copyOf(headers);
    this.controlIdentity = Objects.requireNonNull(controlIdentity, "controlIdentity");
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public boolean supportsPartialMessages() {
    return true;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    session.setTextMessageSizeLimit(MuxLimits.MAX_PAYLOAD_BYTES);
    session.setBinaryMessageSizeLimit(MuxLimits.MAX_PAYLOAD_BYTES);
    IdeBrowserDataStream current =
        new IdeBrowserDataStream(
            session,
            environmentId,
            viewId,
            bindingEpoch,
            rawPathAndQuery,
            headers,
            controlIdentity,
            registry,
            () -> {});
    stream = current;
    current.start();
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    IdeBrowserDataStream current = stream;
    if (current == null
        || !current.offer(
            true, message.getPayload().getBytes(StandardCharsets.UTF_8), message.isLast())) {
      fail(session);
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    ByteBuffer payload = message.getPayload();
    byte[] bytes = new byte[payload.remaining()];
    payload.get(bytes);
    IdeBrowserDataStream current = stream;
    if (current == null || !current.offer(false, bytes, message.isLast())) {
      fail(session);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    IdeBrowserDataStream current = stream;
    if (current != null) {
      current.browserClosed(status);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    IdeBrowserDataStream current = stream;
    if (current != null) {
      current.browserFailed(exception);
    }
    close(session, CloseStatus.SERVER_ERROR);
  }

  private void fail(WebSocketSession session) {
    IdeBrowserDataStream current = stream;
    if (current != null) {
      current.browserFailed(new IOException("browser websocket queue is exhausted"));
    }
    close(session, CloseStatus.POLICY_VIOLATION);
  }

  private static void close(WebSocketSession session, CloseStatus status) {
    synchronized (session) {
      if (!session.isOpen()) {
        return;
      }
      try {
        session.close(status);
      } catch (IOException ignored) {
        // 浏览器已断开。
      }
    }
  }
}
