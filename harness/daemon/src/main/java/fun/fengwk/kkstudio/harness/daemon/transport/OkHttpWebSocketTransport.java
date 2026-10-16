package fun.fengwk.kkstudio.harness.daemon.transport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 OkHttp 的生产 transport。
 *
 * <p><b>强制压缩：</b>OkHttp 在 upgrade 请求中声明 {@code Sec-WebSocket-Extensions: permessage-deflate}，本
 * transport 要求服务端回包精确协商该扩展；缺失时立即以 RFC 6455 close code {@value #MANDATORY_EXTENSION} 关闭且不交付连接，
 * 绝不退化为未压缩会话。
 *
 * <p><b>帧约束：</b>只接受文本帧；单条入站文本超过配置上限（默认 16 MiB 字符）或收到 binary 帧时，确定性发送一次 close code {@value
 * #POLICY_VIOLATION} 并通知断开。
 *
 * <p><b>握手期限：</b>每次连接尝试同时受 OkHttp client 的 {@code callTimeout} 与逻辑 future 的 {@link
 * CompletableFuture#orTimeout} 约束，二者共用同一 2 秒预算；upgrade 成功后 OkHttp 结束该 call 的超时，已建立的长连接不受该预算限制。握手
 * future 的成功结算是对外交付的唯一权威：期限或 close 已结算时，迟到的 onOpen 只能释放原生句柄，绝不复活连接。
 *
 * <p><b>资源所有权：</b>{@link WebSocketDialer} 返回并持有原生 {@link WebSocket}；transport 用一个 {@link Handshake}
 * 集合覆盖在途与已交付的原生句柄。{@link #close()} 幂等、拒绝后续 {@link #connect}、结算所有 pending 握手、取消原生 I/O，并在释放其余资源后 释放
 * dialer 独占的 client。
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

  /** 单次握手预算：TCP 连接与 HTTP upgrade 共用，upgrade 成功后不再约束长连接。 */
  private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(2);

  private final WebSocketDialer dialer;
  private final URI gatewayUri;
  private final int maxInboundTextChars;

  /** 覆盖在途与已交付原生句柄；仅在原生终结回调或 dial 失败时退役，不以逻辑断连代替物理关闭。 */
  private final Set<Handshake> handshakes = ConcurrentHashMap.newKeySet();

  private final Object lifecycleLock = new Object();
  private boolean closed;

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
            .connectTimeout(HANDSHAKE_TIMEOUT)
            // 初始握手整体受 callTimeout 约束；upgrade 成功后 OkHttp 结束该 call 的超时。
            .callTimeout(HANDSHAKE_TIMEOUT)
            // WebSocket 是长连接：读超时留空，连接存活由协议层 heartbeat 判定。
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    return new OkHttpWebSocketDialer(client);
  }

  @Override
  public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
    Objects.requireNonNull(listener, "listener");
    Handshake handshake;
    synchronized (lifecycleLock) {
      if (closed) {
        return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
      }
      handshake = new Handshake(listener, maxInboundTextChars);
      handshakes.add(handshake);
    }
    // dial 可能阻塞，不在 lifecycleLock 内执行；close() 依赖集合成员身份收敛已登记的握手。
    return handshake.start();
  }

  @Override
  public void close() {
    List<Handshake> pending;
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      pending = new ArrayList<>(handshakes);
      handshakes.clear();
    }
    // 单个握手取消或使用者回调失败不阻断其余握手，且必须释放 dialer 独占的 client。
    Throwable failure = null;
    for (Handshake handshake : pending) {
      try {
        handshake.terminate();
      } catch (Throwable error) {
        failure = accumulate(failure, error);
      }
    }
    try {
      dialer.close();
    } catch (Throwable error) {
      failure = accumulate(failure, error);
    }
    if (failure != null) {
      rethrow(failure);
    }
  }

  /** 聚合清理失败：Error 优先作为主因（首个 Error 保留），否则保留首个失败；其余失败作为 suppressed 附加且不自我抑制。 */
  private static Throwable accumulate(Throwable first, Throwable next) {
    if (first == null) {
      return next;
    }
    if (first == next) {
      return first;
    }
    if (next instanceof Error && !(first instanceof Error)) {
      next.addSuppressed(first);
      return next;
    }
    first.addSuppressed(next);
    return first;
  }

  private static void rethrow(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    throw new IllegalStateException("websocket transport close failed", failure);
  }

  /**
   * 建立单条 WebSocket 连接，并拥有关闭底层客户端资源的责任。
   *
   * <p>生产实现不得提供空操作 {@link #close()}：transport 把它作为释放自身独占 client 的唯一入口。
   */
  interface WebSocketDialer extends AutoCloseable {

    /** 发起 upgrade 并返回原生句柄；失败必须以 {@code listener} 回调或异常表达。 */
    WebSocket dial(Request request, WebSocketListener listener);

    /** 取消在途 call、停止自有 dispatcher 并清空连接池；三者互不跳过。 */
    @Override
    void close();
  }

  /** 真实 OkHttp dialer：独占 client，close 时依次取消在途 call、停止 dispatcher、清空连接池。 */
  static final class OkHttpWebSocketDialer implements WebSocketDialer {

    private final OkHttpClient client;

    OkHttpWebSocketDialer(OkHttpClient client) {
      this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public WebSocket dial(Request request, WebSocketListener listener) {
      return client.newWebSocket(request, listener);
    }

    @Override
    public void close() {
      List<Runnable> releases =
          List.of(
              () -> client.dispatcher().cancelAll(),
              () -> client.dispatcher().executorService().shutdown(),
              () -> client.connectionPool().evictAll());
      Throwable failure = null;
      for (Runnable release : releases) {
        try {
          release.run();
        } catch (Throwable error) {
          failure = accumulate(failure, error);
        }
      }
      if (failure != null) {
        rethrow(failure);
      }
    }
  }

  /**
   * 单次握手的仲裁点。
   *
   * <p>{@code result} 是唯一权威：成功结算即交付，失败结算即终结；{@code disconnected} 保证使用者最多观察到一次断开通知。OkHttp 回调、逻辑期限与
   * transport close 都可能竞争，迟到回调只能消费既成事实，绝不复活连接或重复交付。
   */
  private final class Handshake extends WebSocketListener {

    private final CompletableFuture<DaemonConnection> result = new CompletableFuture<>();
    private final CompletionStage<DaemonConnection> completion;
    private final OkHttpWebSocketConnection connection = new OkHttpWebSocketConnection();
    private final DaemonTransportListener delegate;
    private final int maxInboundTextChars;
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicBoolean rejected = new AtomicBoolean();

    private Handshake(DaemonTransportListener delegate, int maxInboundTextChars) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.maxInboundTextChars = maxInboundTextChars;
      // 返回包含收尾结果的 stage，不能把清理失败留在未观察的依赖中。
      completion = result.handle(this::onSettled);
    }

    private CompletionStage<DaemonConnection> start() {
      if (result.isDone()) {
        // transport close 已先行终结本握手：不登记期限，也不发起 upgrade。
        return completion;
      }
      // 逻辑期限：即使原生 call 无法立即取消，future 也必须有界结算。
      result.orTimeout(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      try {
        Request request = new Request.Builder().url(gatewayUri.toASCIIString()).build();
        connection.attach(dialer.dial(request, this));
      } catch (RuntimeException error) {
        handshakes.remove(this);
        result.completeExceptionally(error);
      }
      return completion;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      connection.attach(webSocket);
      if (!permessageDeflateNegotiated(response)) {
        // 未协商必需的传输压缩：按 RFC 6455 以 1010 关闭，不交付未压缩连接。
        reject(webSocket, MANDATORY_EXTENSION, "server did not negotiate permessage-deflate");
        return;
      }
      // 先标记可用，再以 future 成功结算作为唯一交付权威；两者任一失败都必须释放迟到句柄。
      if (!connection.markOpen() || !result.complete(connection)) {
        connection.markClosed();
        connection.abort();
      }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      if (rejected.get() || !deliversMessages()) {
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
      if (rejected.get() || !deliversMessages()) {
        return;
      }
      // 当前 Daemon 协议仅允许 UTF-8 文本帧。
      reject(webSocket, POLICY_VIOLATION, "binary frames are not supported");
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      webSocket.close(code, null);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      handshakes.remove(this);
      if (!result.completeExceptionally(
          new IllegalStateException("websocket closed before open"))) {
        // 已交付连接被远端正常关闭：只补一次断开通知，原因保持为空。
        notifyDisconnected(null);
      }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      handshakes.remove(this);
      if (!result.completeExceptionally(error)) {
        // 已交付连接失效：只补一次断开通知，保留首个失败。
        notifyDisconnected(error);
      }
    }

    /** 是否已有成功结算且仍可发送的连接；入站帧只在此时投递。 */
    private boolean deliversMessages() {
      return result.isDone() && !result.isCompletedExceptionally() && connection.isOpen();
    }

    /** 结果结算后的统一收尾：中止原生 I/O 与投递断开通知都必须 attempt，Error 优先作为主因。 */
    private DaemonConnection onSettled(DaemonConnection settledConnection, Throwable error) {
      if (error == null) {
        return settledConnection;
      }
      Throwable failure = unwrap(error);
      try {
        connection.abortPending();
      } catch (Throwable abortError) {
        failure = accumulate(failure, abortError);
      }
      try {
        notifyDisconnected(unwrap(error));
      } catch (Throwable notifyError) {
        failure = accumulate(failure, notifyError);
      }
      throw new CompletionException(failure);
    }

    /** 确定性拒绝：恰好一次 close 帧、恰好一次断开通知，且不再消费后续帧。 */
    private void reject(WebSocket webSocket, int closeCode, String reason) {
      if (!rejected.compareAndSet(false, true)) {
        return;
      }
      if (webSocket != null) {
        connection.attach(webSocket);
        connection.closeGracefully(closeCode, reason);
      }
      IllegalStateException error = new IllegalStateException(reason);
      if (!result.completeExceptionally(error)) {
        // 已交付连接触发策略拒绝：future 已成功结算，只补一次断开通知。
        notifyDisconnected(error);
      }
    }

    /** transport close 的收敛动作：notify、强制中止与 pending future 结算都必须 attempt，最终按 Error 优先聚合抛出。 */
    private void terminate() {
      IllegalStateException closedError = new IllegalStateException("transport is closed");
      Throwable failure = null;
      try {
        // 直接通知：使用者回调异常必须向 close 调用方传播，而不是被 future 依赖吞掉。
        notifyDisconnected(closedError);
      } catch (Throwable notifyError) {
        failure = accumulate(failure, notifyError);
      }
      try {
        // 即使 close 已请求 graceful close，transport close 也必须强制取消原生句柄。
        connection.abort();
      } catch (Throwable abortError) {
        failure = accumulate(failure, abortError);
      }
      try {
        result.completeExceptionally(closedError);
      } catch (Throwable settlementError) {
        failure = accumulate(failure, settlementError);
      }
      handshakes.remove(this);
      if (failure != null) {
        rethrow(failure);
      }
    }

    /** 恰好一次地投递逻辑断开；graceful close 仍可能等待原生终结回调。 */
    private void notifyDisconnected(Throwable cause) {
      if (!disconnected.compareAndSet(false, true)) {
        return;
      }
      connection.markClosed();
      delegate.onDisconnected(cause);
    }

    private boolean permessageDeflateNegotiated(Response response) {
      String extensions = response.header(EXTENSIONS_HEADER);
      if (extensions == null) {
        return false;
      }
      // 精确匹配扩展名称 token（逗号分组、分号参数之前），不得把 not-permessage-deflate 误判为压缩。
      for (String extension : extensions.split(",")) {
        String name = extension.split(";", 2)[0].trim();
        if (PERMESSAGE_DEFLATE.equalsIgnoreCase(name)) {
          return true;
        }
      }
      return false;
    }
  }

  /** 还原异步结果包装的原始 cause。 */
  private static Throwable unwrap(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
      return error.getCause();
    }
    return error;
  }

  /**
   * OkHttp WebSocket 连接适配：本地入队、存活判定、幂等 graceful close 与中止。
   *
   * <p>{@code attach}/{@code abort} 与关闭状态在同一把锁下协调：同一原生句柄只取消一次，且 close 之后不可再被打开。graceful close 与
   * abort 各自记录请求，abort 不因已请求 graceful close 而跳过。
   */
  static final class OkHttpWebSocketConnection implements DaemonConnection {

    private final Object lock = new Object();
    private WebSocket webSocket;
    private boolean open;
    private boolean closeSent;
    private boolean cancelRequested;
    private boolean cancelPerformed;

    void attach(WebSocket webSocket) {
      synchronized (lock) {
        this.webSocket = webSocket;
        cancelIfRequested();
      }
    }

    /** 仅在尚未请求关闭/中止时标记连接可用；返回是否成功。 */
    boolean markOpen() {
      synchronized (lock) {
        if (closeSent || cancelRequested) {
          return false;
        }
        open = true;
        return true;
      }
    }

    void markClosed() {
      synchronized (lock) {
        open = false;
      }
    }

    /** 发送恰好一次 graceful close 帧；正常关闭与策略拒绝共用同一入口。 */
    void closeGracefully(int code, String reason) {
      synchronized (lock) {
        open = false;
        if (closeSent || cancelRequested) {
          return;
        }
        closeSent = true;
        WebSocket current = webSocket;
        if (current != null) {
          current.close(code, reason);
        }
      }
    }

    /** 失败收尾：仅当尚未请求 graceful close 时取消，避免打断已发出的 close 帧。 */
    void abortPending() {
      synchronized (lock) {
        open = false;
        if (closeSent) {
          return;
        }
        cancelRequested = true;
        cancelIfRequested();
      }
    }

    /** 强制中止：无视已请求的 graceful close 取消原生句柄（transport close / 迟到交付）。 */
    void abort() {
      synchronized (lock) {
        open = false;
        cancelRequested = true;
        cancelIfRequested();
      }
    }

    /** 调用方持锁；同一原生句柄最多取消一次，句柄尚未到达时由 attach 补做。 */
    private void cancelIfRequested() {
      if (cancelRequested && !cancelPerformed && webSocket != null) {
        cancelPerformed = true;
        webSocket.cancel();
      }
    }

    @Override
    public CompletionStage<Void> sendText(String message) {
      Objects.requireNonNull(message, "message");
      WebSocket current;
      synchronized (lock) {
        current = open ? webSocket : null;
      }
      if (current == null) {
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
      closeGracefully(NORMAL_CLOSURE, "daemon stopped");
    }

    @Override
    public boolean isOpen() {
      synchronized (lock) {
        return open;
      }
    }
  }
}
