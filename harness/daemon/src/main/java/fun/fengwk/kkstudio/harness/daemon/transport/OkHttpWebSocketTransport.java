package fun.fengwk.kkstudio.harness.daemon.transport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 OkHttp 的生产 transport。
 *
 * <p><b>强制压缩：</b>OkHttp 在 upgrade 请求中声明 {@code Sec-WebSocket-Extensions: permessage-deflate}，本
 * transport 要求服务端 回包协商成功；缺失或未被接受时立即以 RFC 6455 close code {@value #MANDATORY_EXTENSION}
 * 关闭且不交付连接，绝不退化为未压缩会话。
 *
 * <p><b>帧约束：</b>只接受文本帧；单条入站文本超过配置上限（默认 16 MiB 字符）或收到 binary 帧时，确定性发送一次 close code {@value
 * #POLICY_VIOLATION} 并通知断开。
 *
 * <p>{@link DaemonConnection#sendText(String)} 只表达本地入队结果；异步发送失败由 {@link DaemonConnection#isOpen()}
 * 转为 false 并经断开回调表达，不改变已递交帧“未被对端确认”的语义。
 */
public final class OkHttpWebSocketTransport implements DaemonTransport {

  /** 默认单条入站文本消息的字符上限（16 MiB）。 */
  public static final int DEFAULT_MAX_INBOUND_TEXT_CHARS = 16 * 1024 * 1024;

  /** RFC 6455 policy violation close code，用于确定性拒绝非法入站帧。 */
  static final int POLICY_VIOLATION = 1008;

  /** RFC 6455 mandatory extension close code：服务端未协商必需的扩展。 */
  static final int MANDATORY_EXTENSION = 1010;

  /** RFC 6455 normal closure，用于本地正常关闭。 */
  static final int NORMAL_CLOSURE = 1000;

  static final String PERMESSAGE_DEFLATE = "permessage-deflate";
  static final String EXTENSIONS_HEADER = "Sec-WebSocket-Extensions";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  private final WebSocketDialer dialer;
  private final URI gatewayUri;
  private final int maxInboundTextChars;

  public OkHttpWebSocketTransport(URI gatewayUri) {
    this(defaultDialer(), gatewayUri, DEFAULT_MAX_INBOUND_TEXT_CHARS);
  }

  /** 使用自定义入站文本消息字符上限创建 transport。 */
  public OkHttpWebSocketTransport(URI gatewayUri, int maxInboundTextChars) {
    this(defaultDialer(), gatewayUri, maxInboundTextChars);
  }

  OkHttpWebSocketTransport(WebSocketDialer dialer, URI gatewayUri, int maxInboundTextChars) {
    this.dialer = Objects.requireNonNull(dialer, "dialer");
    this.gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
    if (maxInboundTextChars <= 0) {
      throw new IllegalArgumentException("maxInboundTextChars must be positive");
    }
    this.maxInboundTextChars = maxInboundTextChars;
  }

  private static WebSocketDialer defaultDialer() {
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            // WebSocket 是长连接：读超时留空，连接存活由协议层 heartbeat 判定。
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    return (request, listener) -> client.newWebSocket(request, listener);
  }

  @Override
  public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
    return new Handshake(listener, maxInboundTextChars).start();
  }

  /** 建立单条 WebSocket 连接，供测试替换真实 OkHttp 客户端。 */
  interface WebSocketDialer {

    /** 发起 upgrade；失败必须以 {@code listener} 回调或异常表达。 */
    void dial(Request request, WebSocketListener listener);
  }

  /**
   * 单次握手的仲裁点。
   *
   * <p>OkHttp 回调与本地上送失败可能竞争，{@code settled} 与 {@code disconnected} 保证使用者观察到的永远是「最多一次连接交付 + 最多一次
   * 断开通知」。
   */
  private final class Handshake extends WebSocketListener {

    private final CompletableFuture<DaemonConnection> result = new CompletableFuture<>();
    private final OkHttpWebSocketConnection connection = new OkHttpWebSocketConnection();
    private final DaemonTransportListener delegate;
    private final int maxInboundTextChars;
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicBoolean rejected = new AtomicBoolean();

    private Handshake(DaemonTransportListener delegate, int maxInboundTextChars) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.maxInboundTextChars = maxInboundTextChars;
    }

    private CompletionStage<DaemonConnection> start() {
      Request request = new Request.Builder().url(gatewayUri.toASCIIString()).build();
      try {
        dialer.dial(request, this);
      } catch (RuntimeException error) {
        onFailure(null, error, null);
      }
      return result;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      connection.attach(webSocket);
      if (!permessageDeflateNegotiated(response)) {
        // 未协商必需的传输压缩：按 RFC 6455 以 1010 关闭，不交付未压缩连接。
        reject(webSocket, MANDATORY_EXTENSION, "server did not negotiate permessage-deflate");
        return;
      }
      connection.markOpen();
      if (settled.compareAndSet(false, true)) {
        result.complete(connection);
      }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      if (rejected.get()) {
        return;
      }
      if (text.length() > maxInboundTextChars) {
        reject(webSocket, POLICY_VIOLATION, "inbound text message exceeds the maximum size");
        return;
      }
      delegate.onMessage(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      // 当前 Daemon 协议仅允许 UTF-8 文本帧。
      reject(webSocket, POLICY_VIOLATION, "binary frames are not supported");
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      webSocket.close(code, null);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      notifyDisconnected(null);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      notifyDisconnected(error);
    }

    private boolean permessageDeflateNegotiated(Response response) {
      String extensions = response.header(EXTENSIONS_HEADER);
      return extensions != null && extensions.toLowerCase(Locale.ROOT).contains(PERMESSAGE_DEFLATE);
    }

    /** 确定性拒绝：恰好一次 close 帧、恰好一次断开通知，且不再消费后续帧。 */
    private void reject(WebSocket webSocket, int closeCode, String reason) {
      if (!rejected.compareAndSet(false, true)) {
        return;
      }
      if (webSocket != null) {
        webSocket.close(closeCode, reason);
      }
      notifyDisconnected(new IllegalStateException(reason));
    }

    /** 恰好一次地把断开事件交给使用者；未交付连接同时以异常结束连接阶段。 */
    private void notifyDisconnected(Throwable cause) {
      if (!disconnected.compareAndSet(false, true)) {
        return;
      }
      connection.markClosed();
      if (settled.compareAndSet(false, true)) {
        result.completeExceptionally(
            cause == null ? new IllegalStateException("websocket closed before open") : cause);
      }
      delegate.onDisconnected(cause);
    }
  }

  /** OkHttp WebSocket 连接适配：本地入队、存活判定与幂等关闭。 */
  static final class OkHttpWebSocketConnection implements DaemonConnection {

    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private volatile WebSocket webSocket;

    void attach(WebSocket webSocket) {
      this.webSocket = webSocket;
    }

    void markOpen() {
      open.set(true);
    }

    void markClosed() {
      open.set(false);
    }

    @Override
    public CompletionStage<Void> sendText(String message) {
      Objects.requireNonNull(message, "message");
      WebSocket current = webSocket;
      if (current == null || !isOpen()) {
        return CompletableFuture.failedFuture(new IllegalStateException("websocket is closed"));
      }
      if (!current.send(message)) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("websocket rejected the outbound frame"));
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      open.set(false);
      if (closeRequested.compareAndSet(false, true)) {
        WebSocket current = webSocket;
        if (current != null) {
          current.close(NORMAL_CLOSURE, "daemon stopped");
        }
      }
    }

    @Override
    public boolean isOpen() {
      return open.get();
    }
  }
}
