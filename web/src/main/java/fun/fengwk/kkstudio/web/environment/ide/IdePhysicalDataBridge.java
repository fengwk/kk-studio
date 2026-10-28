package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 非 owner 节点对 Daemon 数据 WebSocket 的整条单跳桥接。
 *
 * <p>不消费 ticket、不解析 mux 帧、不生成逻辑流 ACK；只把完整 binary message 原样转发。owner 侧才是唯一票据消费者。任何一侧失败都关闭另一侧，
 * 不改投其它节点、不自动重连。
 */
final class IdePhysicalDataBridge implements IdeDataPhysicalSocket {

  private static final CloseStatus OWNER_UNAVAILABLE =
      CloseStatus.SERVER_ERROR.withReason("ide data gateway unavailable");

  private final WebSocketClient webSocketClient;
  private final IdeClusterClient clusterInternalClient;
  private final ClusterOwnerRoute owner;
  private final EnvironmentId environmentId;
  private final String ticket;
  private final Duration dialTimeout;
  private final WebSocketSession source;
  private final AtomicBoolean closed = new AtomicBoolean();

  private volatile WebSocketSession target;

  IdePhysicalDataBridge(
      WebSocketClient webSocketClient,
      IdeClusterClient clusterInternalClient,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      String ticket,
      Duration dialTimeout,
      WebSocketSession source) {
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.ticket = Objects.requireNonNull(ticket, "ticket");
    this.dialTimeout = Objects.requireNonNull(dialTimeout, "dialTimeout");
    this.source = Objects.requireNonNull(source, "source");
  }

  void start() {
    String canonicalPath = IdeDataInternalPaths.physicalDataPath(environmentId, ticket);
    URI uri;
    try {
      uri = IdeDataInternalPaths.internalWebSocketUri(owner.internalUrl(), canonicalPath);
    } catch (IllegalArgumentException error) {
      onError(error);
      return;
    }
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    clusterInternalClient.signHeaders("GET", canonicalPath, owner).forEach(headers::add);
    AbstractWebSocketHandler targetHandler =
        new AbstractWebSocketHandler() {
          @Override
          public void afterConnectionEstablished(WebSocketSession session) {
            session.setBinaryMessageSizeLimit(MuxLimits.HEADER_BYTES + MuxLimits.MAX_PAYLOAD_BYTES);
            target = session;
            if (closed.get()) {
              closeQuietly(session, OWNER_UNAVAILABLE);
            }
          }

          @Override
          protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            sendToSource(message);
          }

          @Override
          protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            onError(new IOException("IDE data bridge received a text message"));
          }

          @Override
          public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeSource(relayable(status));
          }

          @Override
          public void handleTransportError(WebSocketSession session, Throwable exception) {
            onError(exception);
          }
        };
    try {
      WebSocketSession opened =
          webSocketClient
              .execute(targetHandler, headers, uri)
              .get(dialTimeout.toMillis(), TimeUnit.MILLISECONDS);
      target = opened;
      if (closed.get()) {
        closeQuietly(opened, OWNER_UNAVAILABLE);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      onError(error);
    } catch (ExecutionException | TimeoutException | RuntimeException error) {
      onError(error);
    }
  }

  @Override
  public void onBinary(byte[] frame) {
    WebSocketSession current = target;
    if (current == null || !current.isOpen()) {
      onError(new IOException("IDE data bridge owner connection is unavailable"));
      return;
    }
    synchronized (current) {
      try {
        current.sendMessage(new BinaryMessage(frame, true));
      } catch (IOException | RuntimeException error) {
        onError(error);
      }
    }
  }

  @Override
  public void onPong() {
    // 每一跳使用各自的原生 Ping/Pong；中继不把 Pong 转成业务帧。
  }

  @Override
  public void onClosed(CloseStatus status) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    WebSocketSession current = target;
    target = null;
    if (current != null && current.isOpen()) {
      try {
        current.close(relayable(status));
      } catch (IOException ignored) {
        // 对端已断开。
      }
    }
  }

  @Override
  public void onError(Throwable error) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    WebSocketSession current = target;
    target = null;
    if (current != null && current.isOpen()) {
      closeQuietly(current, OWNER_UNAVAILABLE);
    }
    closeSourceInternal(OWNER_UNAVAILABLE);
  }

  private void sendToSource(BinaryMessage message) {
    if (!source.isOpen()) {
      onError(new IOException("IDE data bridge source is closed"));
      return;
    }
    synchronized (source) {
      try {
        source.sendMessage(message);
      } catch (IOException | RuntimeException error) {
        onError(error);
      }
    }
  }

  private void closeSource(CloseStatus status) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    closeSourceInternal(status);
  }

  private void closeSourceInternal(CloseStatus status) {
    synchronized (source) {
      if (!source.isOpen()) {
        return;
      }
      try {
        source.close(status);
      } catch (IOException ignored) {
        // 已断开。
      }
    }
  }

  private static void closeQuietly(WebSocketSession session, CloseStatus status) {
    synchronized (session) {
      if (!session.isOpen()) {
        return;
      }
      try {
        session.close(status);
      } catch (IOException ignored) {
        // 已断开。
      }
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
    return CloseStatus.SERVER_ERROR.withReason("ide data gateway closed");
  }
}
