package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 非 owner 节点把浏览器逻辑 WebSocket 单跳到 READY owner 的内部数据端点。
 *
 * <p>本类不解析 mux、不生成 ACK、不保存浏览器会话：owner 侧用转发的 IDE cookie 完成 view+epoch 授权并建立逻辑流。两侧只透传原生 partial 分段与
 * FIN，任一侧断开即关闭另一侧，绝不自动重连或改投其它节点。
 */
@Slf4j
final class IdeRemoteBrowserDataBridge extends AbstractWebSocketHandler {

  private static final CloseStatus OWNER_UNAVAILABLE =
      CloseStatus.SERVER_ERROR.withReason("ide gateway unavailable");

  private final WebSocketClient webSocketClient;
  private final IdeClusterClient clusterInternalClient;
  private final ClusterOwnerRoute owner;
  private final EnvironmentId environmentId;
  private final UUID viewId;
  private final String rawPathAndQuery;
  private final List<MuxHeader> forwardedHeaders;
  private final String browserScheme;
  private final Duration dialTimeout;
  private final WebSocketSession browser;
  private final AtomicBoolean closed = new AtomicBoolean();

  private volatile WebSocketSession ownerSession;

  IdeRemoteBrowserDataBridge(
      WebSocketClient webSocketClient,
      IdeClusterClient clusterInternalClient,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      List<MuxHeader> forwardedHeaders,
      String browserScheme,
      Duration dialTimeout,
      WebSocketSession browser) {
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.viewId = Objects.requireNonNull(viewId, "viewId");
    this.rawPathAndQuery = Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    this.forwardedHeaders = List.copyOf(forwardedHeaders);
    this.browserScheme = Objects.requireNonNull(browserScheme, "browserScheme");
    this.dialTimeout = Objects.requireNonNull(dialTimeout, "dialTimeout");
    this.browser = Objects.requireNonNull(browser, "browser");
    if (dialTimeout.isZero() || dialTimeout.isNegative()) {
      throw new IllegalArgumentException("dialTimeout must be positive");
    }
  }

  @Override
  public boolean supportsPartialMessages() {
    return true;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    browser.setTextMessageSizeLimit(MuxLimits.MAX_PAYLOAD_BYTES);
    browser.setBinaryMessageSizeLimit(MuxLimits.MAX_PAYLOAD_BYTES);
    dialOwner();
  }

  private void dialOwner() {
    String canonicalPath =
        IdeDataInternalPaths.dataWebSocketPath(environmentId, viewId, rawPathAndQuery);
    URI target;
    try {
      target = IdeDataInternalPaths.internalWebSocketUri(owner.internalUrl(), canonicalPath);
    } catch (IllegalArgumentException error) {
      shutdown(OWNER_UNAVAILABLE);
      return;
    }
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    for (MuxHeader header : forwardedHeaders) {
      headers.add(header.name(), header.value());
    }
    clusterInternalClient.signHeaders("GET", canonicalPath, owner).forEach(headers::add);
    headers.add(IdeForwardedRequest.SCHEME_HEADER, browserScheme);

    AbstractWebSocketHandler ownerHandler =
        new AbstractWebSocketHandler() {
          @Override
          public boolean supportsPartialMessages() {
            return true;
          }

          @Override
          public void afterConnectionEstablished(WebSocketSession session) {
            log.debug("remote IDE browser data owner connection established");
            session.setTextMessageSizeLimit(MuxLimits.MAX_PAYLOAD_BYTES);
            session.setBinaryMessageSizeLimit(MuxLimits.MAX_PAYLOAD_BYTES);
            ownerSession = session;
            if (closed.get()) {
              closeQuietly(session);
            }
          }

          @Override
          protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            sendBrowserText(message.getPayload(), message.isLast());
          }

          @Override
          protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            ByteBuffer payload = message.getPayload();
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            sendBrowserBinary(bytes, message.isLast());
          }

          @Override
          public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            shutdown(relayable(status));
          }

          @Override
          public void handleTransportError(WebSocketSession session, Throwable exception) {
            shutdown(OWNER_UNAVAILABLE);
          }
        };
    try {
      WebSocketSession opened =
          webSocketClient
              .execute(ownerHandler, headers, target)
              .get(dialTimeout.toMillis(), TimeUnit.MILLISECONDS);
      ownerSession = opened;
      if (closed.get()) {
        closeQuietly(opened);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      log.info("remote IDE browser data owner dial interrupted");
      shutdown(OWNER_UNAVAILABLE);
    } catch (ExecutionException | TimeoutException | RuntimeException error) {
      Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
      log.info(
          "remote IDE browser data owner dial failed; exception={}; message={}",
          cause.getClass().getName(),
          cause.getMessage());
      shutdown(OWNER_UNAVAILABLE);
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    sendOwner(new TextMessage(message.getPayload(), message.isLast()));
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    ByteBuffer payload = message.getPayload();
    byte[] bytes = new byte[payload.remaining()];
    payload.get(bytes);
    sendOwner(new BinaryMessage(ByteBuffer.wrap(bytes), message.isLast()));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    shutdown(relayable(status));
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    shutdown(CloseStatus.SERVER_ERROR);
  }

  private void sendOwner(WebSocketMessage<?> message) {
    WebSocketSession current = ownerSession;
    if (current == null || !current.isOpen()) {
      shutdown(OWNER_UNAVAILABLE);
      return;
    }
    synchronized (current) {
      try {
        current.sendMessage(message);
      } catch (IOException | RuntimeException error) {
        shutdown(OWNER_UNAVAILABLE);
      }
    }
  }

  private void sendBrowserText(String text, boolean last) {
    synchronized (browser) {
      try {
        Session nativeSession = nativeSession();
        if (nativeSession != null && nativeSession.isOpen()) {
          RemoteEndpoint.Basic remote = nativeSession.getBasicRemote();
          remote.sendText(text, last);
        } else {
          browser.sendMessage(new TextMessage(text, last));
        }
      } catch (IOException | RuntimeException error) {
        shutdown(CloseStatus.SERVER_ERROR);
      }
    }
  }

  private void sendBrowserBinary(byte[] bytes, boolean last) {
    synchronized (browser) {
      try {
        Session nativeSession = nativeSession();
        if (nativeSession != null && nativeSession.isOpen()) {
          RemoteEndpoint.Basic remote = nativeSession.getBasicRemote();
          remote.sendBinary(ByteBuffer.wrap(bytes), last);
        } else {
          browser.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes), last));
        }
      } catch (IOException | RuntimeException error) {
        shutdown(CloseStatus.SERVER_ERROR);
      }
    }
  }

  private Session nativeSession() {
    if (browser instanceof NativeWebSocketSession nativeSession) {
      return nativeSession.getNativeSession(Session.class);
    }
    return null;
  }

  private void shutdown(CloseStatus status) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    WebSocketSession current = ownerSession;
    ownerSession = null;
    if (current != null && current.isOpen()) {
      try {
        current.close(status);
      } catch (IOException ignored) {
        // owner 已断开。
      }
    }
    synchronized (browser) {
      if (!browser.isOpen()) {
        return;
      }
      try {
        browser.close(status);
      } catch (IOException ignored) {
        // 浏览器已断开。
      }
    }
  }

  private static void closeQuietly(WebSocketSession session) {
    try {
      session.close(OWNER_UNAVAILABLE);
    } catch (IOException ignored) {
      // 已断开。
    }
  }

  private static CloseStatus relayable(CloseStatus status) {
    int code = status.getCode();
    if (code >= 3000 && code <= 4999) {
      return new CloseStatus(code, status.getReason());
    }
    if (code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006) {
      return new CloseStatus(code, status.getReason());
    }
    return CloseStatus.SERVER_ERROR.withReason("ide gateway closed");
  }
}
