package fun.fengwk.kkstudio.harness.daemon.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 JDK {@link WebSocket} 的生产 transport。
 *
 * <p>入站只接受文本帧：fragment 累积长度超过可配置上限或出现任何 binary 帧时确定性拒绝——发送一次 policy-violation close 并 通知
 * disconnect，不再继续累积或消费。默认上限对齐 16 MiB（字符）。
 */
public final class JdkWebSocketTransport implements DaemonTransport {

  /** 默认单条入站文本消息的字符上限（16 MiB）。 */
  public static final int DEFAULT_MAX_INBOUND_TEXT_CHARS = 16 * 1024 * 1024;

  /** RFC 6455 policy violation close code，用于确定性拒绝非法入站帧。 */
  static final int POLICY_VIOLATION = 1008;

  private final HttpClient httpClient;
  private final URI gatewayUri;
  private final int maxInboundTextChars;

  public JdkWebSocketTransport(URI gatewayUri) {
    this(HttpClient.newHttpClient(), gatewayUri, DEFAULT_MAX_INBOUND_TEXT_CHARS);
  }

  /** 使用自定义入站文本消息字符上限创建 transport。 */
  public JdkWebSocketTransport(URI gatewayUri, int maxInboundTextChars) {
    this(HttpClient.newHttpClient(), gatewayUri, maxInboundTextChars);
  }

  JdkWebSocketTransport(HttpClient httpClient, URI gatewayUri, int maxInboundTextChars) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
    if (maxInboundTextChars <= 0) {
      throw new IllegalArgumentException("maxInboundTextChars must be positive");
    }
    this.maxInboundTextChars = maxInboundTextChars;
  }

  @Override
  public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
    return httpClient
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .buildAsync(gatewayUri, new ListenerAdapter(listener, maxInboundTextChars))
        .thenApply(ConnectionAdapter::new);
  }

  static final class ConnectionAdapter implements DaemonConnection {

    private final WebSocket webSocket;
    private final AtomicBoolean open = new AtomicBoolean(true);

    ConnectionAdapter(WebSocket webSocket) {
      this.webSocket = webSocket;
    }

    @Override
    public CompletionStage<Void> sendText(String message) {
      if (!isOpen()) {
        return CompletableFuture.failedFuture(new IllegalStateException("websocket is closed"));
      }
      return webSocket.sendText(message, true).thenApply(ignored -> null);
    }

    @Override
    public void close() {
      if (open.compareAndSet(true, false)) {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "daemon stopped");
      }
    }

    @Override
    public boolean isOpen() {
      return open.get() && !webSocket.isOutputClosed();
    }
  }

  static final class ListenerAdapter implements WebSocket.Listener {

    private final DaemonTransportListener listener;
    private final int maxInboundTextChars;
    private final StringBuilder text = new StringBuilder();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicBoolean rejected = new AtomicBoolean();

    ListenerAdapter(DaemonTransportListener listener, int maxInboundTextChars) {
      this.listener = Objects.requireNonNull(listener, "listener");
      if (maxInboundTextChars <= 0) {
        throw new IllegalArgumentException("maxInboundTextChars must be positive");
      }
      this.maxInboundTextChars = maxInboundTextChars;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      if (rejected.get()) {
        return CompletableFuture.completedFuture(null);
      }
      if (text.length() + data.length() > maxInboundTextChars) {
        reject(webSocket, "inbound text message exceeds the maximum size");
        return CompletableFuture.failedFuture(
            new IllegalStateException("inbound text message exceeds the maximum size"));
      }
      text.append(data);
      if (last) {
        String message = text.toString();
        text.setLength(0);
        listener.onMessage(message);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      reject(webSocket, "binary frames are not supported");
      return CompletableFuture.failedFuture(
          new IllegalStateException("binary frames are not supported"));
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      notifyDisconnected(null);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      notifyDisconnected(error);
    }

    /** 确定性拒绝：恰好一次 close，不再消费后续帧，并立即通知运行时断开。 */
    private void reject(WebSocket webSocket, String reason) {
      if (rejected.compareAndSet(false, true)) {
        webSocket.sendClose(POLICY_VIOLATION, reason);
        notifyDisconnected(new IllegalStateException(reason));
      }
    }

    private void notifyDisconnected(Throwable cause) {
      if (disconnected.compareAndSet(false, true)) {
        listener.onDisconnected(cause);
      }
    }
  }
}
