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
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.ide.protocol.IdeHeader;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 非 owner 节点把浏览器 WS 单跳到 READY owner 的内部数据端点，并在两侧之间做原生分段透传。
 *
 * <p>非 owner 不持有浏览器会话状态：cookie/代际鉴权在 owner 侧完成，本类只把浏览器的 partial 帧逐字透传（文本/二进制 + FIN），不解释、不重组、
 * 不改写、不二次 ACK。
 *
 * <p><b>没有中间队列、没有轮询线程：</b>owner 连接就是 {@link WebSocketSession} 句柄本身。两个方向各自直接发送——owner 侧的 Spring
 * 消息回调直接把分段写浏览器原生会话，浏览器的分段在 {@link #ownerLock} 内直接写 owner 会话。该锁同时保证「分段顺序」与「握手竞态下的反压」： owner
 * 尚未建立时浏览器线程在此有界阻塞（真实 TCP 反压），以拨号 deadline 为上限，超时即 fail-closed 关闭浏览器连接。
 *
 * <p><b>硬拨号 deadline：</b>{@code execute} 返回的 future 只用于失败/超时收尾；超时后迟到的成功回调仍会看到真实会话，因此 {@code
 * afterConnectionEstablished} 与 future 回调两处一起保证「已放弃的迟到连接立即关闭」，绝不留下悬空 socket。
 *
 * <p><b>锁序约定：</b>只允许 {@code browser → ownerLock}（浏览器发送失败时收尾）。因此 {@link #closeBrowser} 绝不在持有 {@link
 * #ownerLock} 时调用。
 */
@Slf4j
final class IdeRemoteBrowserBridge {

  /**
   * 单跳到 owner 的固定失败收尾：只回通用原因，绝不泄漏内部地址、状态码或异常细节。
   *
   * <p>只用 Jakarta 规范内合法的 1011（{@link CloseStatus#SERVER_ERROR}）承载固定原因：1014 虽在 WS 库中存在，但 Jakarta
   * 实际关闭时会拒绝， 导致收尾退化为异常而不是一次干净关闭。
   */
  private static final CloseStatus OWNER_UNAVAILABLE =
      CloseStatus.SERVER_ERROR.withReason("ide gateway unavailable");

  /** 一个浏览器分段：文本或二进制，{@code last} 是原生 FIN 标记。 */
  private sealed interface Frame {

    boolean last();
  }

  private record TextFrame(String text, boolean last) implements Frame {}

  private record BinaryFrame(byte[] payload, boolean last) implements Frame {}

  private final WebSocketClient webSocketClient;
  private final IdeClusterClient clusterInternalClient;
  private final ClusterOwnerRoute owner;
  private final EnvironmentId environmentId;
  private final String rawPathAndQuery;
  private final List<IdeHeader> forwardedHeaders;
  private final String browserScheme;
  private final Duration dialTimeout;
  private final WebSocketSession browser;

  /** 浏览器 → owner 的有序通路：owner 会话的发布与逐帧发送都由它串行化（见类注释的锁序约定）。 */
  private final Object ownerLock = new Object();

  private WebSocketSession ownerSession;

  private final AtomicBoolean closed = new AtomicBoolean();

  IdeRemoteBrowserBridge(
      WebSocketClient webSocketClient,
      IdeClusterClient clusterInternalClient,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      String rawPathAndQuery,
      List<IdeHeader> forwardedHeaders,
      String browserScheme,
      Duration dialTimeout,
      WebSocketSession browser) {
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.rawPathAndQuery = Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    this.forwardedHeaders = Objects.requireNonNull(forwardedHeaders, "forwardedHeaders");
    this.browserScheme = Objects.requireNonNull(browserScheme, "browserScheme");
    this.dialTimeout = Objects.requireNonNull(dialTimeout, "dialTimeout");
    this.browser = Objects.requireNonNull(browser, "browser");
    if (dialTimeout.isZero() || dialTimeout.isNegative()) {
      throw new IllegalArgumentException("dialTimeout must be positive");
    }
  }

  /** 建立到 owner 的内部 WS，并把 owner 侧的每个分段直接回传给浏览器。 */
  void start() {
    String canonicalPath = IdeInternalPaths.dataWsPath(environmentId, rawPathAndQuery);
    URI target;
    try {
      target = internalWebSocketUri(owner.internalUrl(), canonicalPath);
    } catch (IllegalArgumentException error) {
      log.debug("cannot forward browser ide websocket: {}", error.getMessage());
      closeBrowser(OWNER_UNAVAILABLE);
      return;
    }
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    for (IdeHeader header : forwardedHeaders) {
      headers.add(header.name(), header.value());
    }
    clusterInternalClient.signHeaders("GET", canonicalPath, owner).forEach(headers::add);
    headers.add(IdeForwardedRequest.SCHEME_HEADER, browserScheme);
    CompletableFuture<WebSocketSession> dial =
        webSocketClient.execute(
            new AbstractWebSocketHandler() {
              @Override
              public boolean supportsPartialMessages() {
                return true;
              }

              @Override
              public void afterConnectionEstablished(WebSocketSession session) {
                synchronized (ownerLock) {
                  if (closed.get()) {
                    // 已经超时放弃：立即回收真实资源，绝不复活已放弃的桥。
                    closeQuietly(session);
                    return;
                  }
                  ownerSession = session;
                  ownerLock.notifyAll();
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
                closeBrowser(OWNER_UNAVAILABLE);
              }

              @Override
              public void handleTransportError(WebSocketSession session, Throwable exception) {
                closeBrowser(OWNER_UNAVAILABLE);
              }
            },
            headers,
            target);
    dial.whenComplete(
        (session, error) -> {
          if (error != null) {
            log.debug("browser ide remote bridge dial failed: {}", error.getMessage());
            closeBrowser(OWNER_UNAVAILABLE);
            return;
          }
          // 成功路径已在 afterConnectionEstablished 发布；这里只兜住「放弃后才到达」的迟到会话。
          if (closed.get()) {
            closeQuietly(session);
          }
        });
    // 硬拨号 deadline：超时即放弃在途拨号（迟到会话由上面的回调回收），并 fail-closed 关闭浏览器侧。
    Thread.ofVirtual()
        .name("ide-remote-bridge-deadline-" + browser.getId())
        .start(
            () -> {
              try {
                dial.get(dialTimeout.toMillis(), TimeUnit.MILLISECONDS);
              } catch (TimeoutException error) {
                log.debug(
                    "browser ide remote bridge dial timed out after {}ms", dialTimeout.toMillis());
                dial.cancel(true);
                closeBrowser(OWNER_UNAVAILABLE);
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              } catch (ExecutionException ignored) {
                // 拨号失败由 whenComplete 统一收尾。
              }
            });
  }

  /** 浏览器 → owner：文本分段原样透传（含 FIN）。 */
  void forwardBrowserText(String text, boolean last) {
    forward(new TextFrame(text, last));
  }

  /** 浏览器 → owner：二进制分段原样透传（含 FIN）。 */
  void forwardBrowserBinary(byte[] bytes, boolean last) {
    forward(new BinaryFrame(bytes, last));
  }

  /**
   * 在 {@link #ownerLock} 内把分段写到 owner 会话：owner 未建立时有界阻塞（反压），建立后按序直发。
   *
   * <p>失败收尾一律在锁外执行，保持 {@code browser → ownerLock} 的锁序。
   */
  private void forward(Frame frame) {
    long deadlineNanos = System.nanoTime() + dialTimeout.toNanos();
    boolean ownerUnavailable = false;
    synchronized (ownerLock) {
      while (ownerSession == null) {
        if (closed.get()) {
          return;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
          ownerUnavailable = true;
          break;
        }
        try {
          ownerLock.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          ownerUnavailable = true;
          break;
        }
      }
      if (!ownerUnavailable) {
        ownerUnavailable = !sendOwner(ownerSession, frame);
      }
    }
    if (ownerUnavailable) {
      closeBrowser(OWNER_UNAVAILABLE);
    }
  }

  /** 调用方必须持有 {@link #ownerLock}；返回 false 表示 owner 会话已不可用。 */
  private static boolean sendOwner(WebSocketSession session, Frame frame) {
    if (session == null || !session.isOpen()) {
      return false;
    }
    try {
      switch (frame) {
        case TextFrame text -> session.sendMessage(new TextMessage(text.text(), text.last()));
        case BinaryFrame binary -> session.sendMessage(
            new BinaryMessage(binary.payload(), binary.last()));
      }
      return true;
    } catch (IOException | RuntimeException error) {
      return false;
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

  /** 浏览器断开/失败：关闭 owner 连接，并唤醒所有等待反压的线程。 */
  void closeOwner() {
    if (closed.compareAndSet(false, true)) {
      WebSocketSession session;
      synchronized (ownerLock) {
        session = ownerSession;
        ownerSession = null;
        ownerLock.notifyAll();
      }
      if (session != null) {
        closeQuietly(session);
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

  private static void closeQuietly(WebSocketSession session) {
    if (session == null) {
      return;
    }
    try {
      session.close(OWNER_UNAVAILABLE);
    } catch (IOException ignored) {
      // 会话已不可用。
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
