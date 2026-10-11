package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeHeader;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 非 owner 节点把浏览器 WS 单跳到 READY owner 的内部数据端点，并在两侧之间做**原生分段**桥接。
 *
 * <p>非 owner 不持有浏览器会话状态：cookie/代际鉴权在 owner 侧完成，本类只把浏览器的 partial 帧逐字透传（文本/二进制 + FIN），
 * 不解释、不重组、不改写、不二次 ACK。owner 侧帧经有界入站队列回传，超界或任一侧断开即 fail-closed 关闭浏览器连接，绝不无界缓冲。
 */
@Slf4j
final class IdeRemoteBrowserBridge {

  private static final long POLL_TICK_MILLIS = 5L;

  /** owner 连接建立前允许暂存的浏览器分段数：只覆盖握手竞态，绝不作为长期缓冲。 */
  private static final int MAX_PENDING_FRAMES = 64;

  private final WebSocketClient webSocketClient;
  private final ClusterInternalClient clusterInternalClient;
  private final ClusterIdeOwnerRoute owner;
  private final EnvironmentId environmentId;
  private final String rawPathAndQuery;
  private final List<IdeHeader> forwardedHeaders;
  private final String browserScheme;
  private final WebSocketSession browser;
  private final AtomicReference<WebSocketIdeStreamPeer> ownerPeer = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  /** owner 连接建立前的浏览器分段：有界暂存，避免握手竞态丢帧，超界即 fail-closed。 */
  private final Deque<IdeStreamPeer.Inbound> pending = new ArrayDeque<>();

  IdeRemoteBrowserBridge(
      WebSocketClient webSocketClient,
      ClusterInternalClient clusterInternalClient,
      ClusterIdeOwnerRoute owner,
      EnvironmentId environmentId,
      String rawPathAndQuery,
      List<IdeHeader> forwardedHeaders,
      String browserScheme,
      WebSocketSession browser) {
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.rawPathAndQuery = Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    this.forwardedHeaders = Objects.requireNonNull(forwardedHeaders, "forwardedHeaders");
    this.browserScheme = Objects.requireNonNull(browserScheme, "browserScheme");
    this.browser = Objects.requireNonNull(browser, "browser");
  }

  /** 建立到 owner 的内部 WS 并开始把 owner 侧分段回传给浏览器。 */
  void start() {
    String canonicalPath = ClusterInternalPaths.dataWsPath(environmentId, rawPathAndQuery);
    URI target;
    try {
      target = internalWebSocketUri(owner.internalUrl(), canonicalPath);
    } catch (IllegalArgumentException error) {
      log.debug("cannot forward browser ide websocket: {}", error.getMessage());
      closeBrowser(CloseStatus.SERVER_ERROR);
      return;
    }
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    for (IdeHeader header : forwardedHeaders) {
      headers.add(header.name(), header.value());
    }
    clusterInternalClient.signHeaders("GET", canonicalPath, owner).forEach(headers::add);
    headers.add(IdeForwardedRequest.SCHEME_HEADER, browserScheme);
    webSocketClient
        .execute(
            new AbstractWebSocketHandler() {
              @Override
              public boolean supportsPartialMessages() {
                return true;
              }

              @Override
              public void afterConnectionEstablished(WebSocketSession session) {
                WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
                ownerPeer.set(peer);
                flushPending(peer);
                Thread.ofVirtual()
                    .name("ide-browser-remote-bridge-" + browser.getId())
                    .start(() -> pumpOwnerToBrowser(peer));
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
                WebSocketIdeStreamPeer peer = ownerPeer.get();
                if (peer != null) {
                  peer.onTransportClosed();
                }
                closeBrowser(CloseStatus.SERVER_ERROR);
              }

              @Override
              public void handleTransportError(WebSocketSession session, Throwable exception) {
                afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
              }
            },
            headers,
            target)
        .whenComplete(
            (session, error) -> {
              if (error != null) {
                log.debug("browser ide remote bridge dial failed: {}", error.getMessage());
                closeBrowser(CloseStatus.SERVER_ERROR);
              }
            });
  }

  /** owner 连接建立后按序补发暂存的浏览器分段。 */
  private void flushPending(WebSocketIdeStreamPeer peer) {
    while (true) {
      IdeStreamPeer.Inbound frame;
      synchronized (pending) {
        frame = pending.pollFirst();
      }
      if (frame == null) {
        return;
      }
      send(peer, frame);
    }
  }

  /** 浏览器 → owner：原生分段发送，保持 FIN 与类型；owner 尚未连上时按序有界暂存。 */
  void forwardBrowserText(String text, boolean last) {
    forward(new IdeStreamPeer.Inbound.Text(text, last));
  }

  void forwardBrowserBinary(byte[] bytes, boolean last) {
    forward(new IdeStreamPeer.Inbound.Binary(bytes, last));
  }

  private void forward(IdeStreamPeer.Inbound frame) {
    WebSocketIdeStreamPeer peer = ownerPeer.get();
    if (peer != null) {
      send(peer, frame);
      return;
    }
    synchronized (pending) {
      if (ownerPeer.get() == null && pending.size() < MAX_PENDING_FRAMES) {
        pending.addLast(frame);
        return;
      }
    }
    // 暂存超界或连接已建立：直接发送，失败即收尾。
    WebSocketIdeStreamPeer current = ownerPeer.get();
    if (current != null) {
      send(current, frame);
    } else {
      closeBrowser(CloseStatus.POLICY_VIOLATION);
    }
  }

  private void send(WebSocketIdeStreamPeer peer, IdeStreamPeer.Inbound frame) {
    if (frame instanceof IdeStreamPeer.Inbound.Text text) {
      peer.sendText(text.json(), text.last());
    } else {
      IdeStreamPeer.Inbound.Binary binary = (IdeStreamPeer.Inbound.Binary) frame;
      peer.sendBinary(binary.frame(), binary.last());
    }
  }

  private void pumpOwnerToBrowser(WebSocketIdeStreamPeer peer) {
    try {
      while (!closed.get()) {
        IdeStreamPeer.Inbound frame = peer.poll(Duration.ofMillis(POLL_TICK_MILLIS));
        if (frame == null) {
          if (peer.closed()) {
            closeBrowser(CloseStatus.SERVER_ERROR);
            return;
          }
          continue;
        }
        if (frame instanceof IdeStreamPeer.Inbound.Text text) {
          sendBrowserText(text.json(), text.last());
        } else {
          IdeStreamPeer.Inbound.Binary binary = (IdeStreamPeer.Inbound.Binary) frame;
          sendBrowserBinary(binary.frame(), binary.last());
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException error) {
      log.debug("browser ide remote bridge failed: {}", error.getMessage());
    } finally {
      closeBrowser(CloseStatus.SERVER_ERROR);
    }
  }

  /** 用浏览器侧原生会话发送 partial 帧；浏览器断开即整体收尾。 */
  private void sendBrowserText(String text, boolean last) {
    if (text.isEmpty() && !last) {
      return;
    }
    synchronized (browser) {
      Session nativeSession = nativeSession();
      if (nativeSession == null || !nativeSession.isOpen()) {
        closeOwner();
        return;
      }
      RemoteEndpoint.Basic remote = nativeSession.getBasicRemote();
      try {
        remote.sendText(text, last);
      } catch (IOException | RuntimeException error) {
        closeOwner();
      }
    }
  }

  private void sendBrowserBinary(byte[] bytes, boolean last) {
    if (bytes.length == 0 && !last) {
      return;
    }
    synchronized (browser) {
      Session nativeSession = nativeSession();
      if (nativeSession == null || !nativeSession.isOpen()) {
        closeOwner();
        return;
      }
      RemoteEndpoint.Basic remote = nativeSession.getBasicRemote();
      try {
        remote.sendBinary(ByteBuffer.wrap(bytes), last);
      } catch (IOException | RuntimeException error) {
        closeOwner();
      }
    }
  }

  private Session nativeSession() {
    if (browser instanceof NativeWebSocketSession nativeWebSocket) {
      Object session = nativeWebSocket.getNativeSession();
      if (session instanceof Session jakartaSession) {
        return jakartaSession;
      }
    }
    return null;
  }

  /** 浏览器断开/失败：关闭 owner 侧连接。 */
  void closeOwner() {
    if (closed.compareAndSet(false, true)) {
      WebSocketIdeStreamPeer peer = ownerPeer.get();
      if (peer != null) {
        peer.close();
      }
    }
  }

  private void closeBrowser(CloseStatus status) {
    closeOwner();
    synchronized (browser) {
      if (browser.isOpen()) {
        try {
          browser.close(status);
        } catch (IOException ignored) {
          // 浏览器侧已断开。
        }
      }
    }
  }

  /** 由内部 origin 与 canonical path 构造内部 WS 地址；只接受裸 http(s) origin。 */
  private static URI internalWebSocketUri(URI internalUrl, String canonicalPath) {
    if (internalUrl == null) {
      throw new IllegalArgumentException("owner route has no internal origin");
    }
    String scheme = internalUrl.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException("owner internal origin has no scheme");
    }
    String webSocketScheme =
        switch (scheme.toLowerCase(Locale.ROOT)) {
          case "http" -> "ws";
          case "https" -> "wss";
          default -> throw new IllegalArgumentException("owner internal origin must be http(s)");
        };
    if (internalUrl.getHost() == null
        || internalUrl.getUserInfo() != null
        || internalUrl.getQuery() != null
        || internalUrl.getFragment() != null) {
      throw new IllegalArgumentException("owner internal origin must be a bare origin");
    }
    return URI.create(webSocketScheme + "://" + internalUrl.getAuthority() + canonicalPath);
  }
}
