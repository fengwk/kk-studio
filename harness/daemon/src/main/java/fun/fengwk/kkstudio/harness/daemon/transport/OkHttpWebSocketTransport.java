package fun.fengwk.kkstudio.harness.daemon.transport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationOutbox;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 基于 OkHttp 的生产 transport。
 *
 * <p><b>唯一物理载体：</b>所有逻辑消息，包括 {@code count=1} 的 HELLO/READY/INVOKE 与短控制帧，都经共享 {@link
 * NotificationPeerLink}（共享 carrier + reassembler + outbox）分片；不存在 raw JSON 旁路。publisher 为本进程
 * endpoint UUID，在 transport 构造期生成一次，同一 transport 的重连复用同一身份。
 *
 * <p><b>借用 Runtime 的 I/O 资源：</b>transport 不创建也不关闭任何执行器。出站 drain 交给注入的 {@code senderExecutor}，peer
 * 重组到期扫描交给注入的 {@code timer}（生产必须非 null）；{@link #close()} 只围栏新连接并释放自己拥有的 handshake、socket、link 与
 * timer future。每次 drain 任务最多发送共享 outbox 给出的一个 {@code sendBatchFrames} 批，末批后若仍有 pending
 * 再调度下一任务，调用线程绝不内联把整包上千片写完；公平轮转完全由共享 outbox 负责，transport 不重写调度算法。
 *
 * <p><b>强制压缩：</b>OkHttp 在 upgrade 请求中声明 {@code Sec-WebSocket-Extensions: permessage-deflate}，本
 * transport 要求服务端回包协商成功；缺失或未被接受时立即以 RFC 6455 close code {@value #MANDATORY_EXTENSION}
 * 关闭且不交付连接，绝不退化为未压缩会话。
 *
 * <p><b>帧约束：</b>只接受文本帧；单条入站文本必须是通过共享 carrier 严格解码的合法片（物理上限 {@link
 * NotificationCarrier#PAYLOAD_LIMIT}），binary 帧、raw JSON 或超限文本确定性拒绝为 close code {@value
 * #POLICY_VIOLATION} 并通知断开。
 *
 * <p>{@link DaemonConnection#sendText(String)} 只在内存中完成有界入队，整包最后一片被 native {@code send}
 * 接受后正常完成，失败/关闭时异常完成；不表达对端确认，也不重试。
 */
public final class OkHttpWebSocketTransport implements DaemonTransport {

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
  private final ExecutorService senderExecutor;
  private final ScheduledExecutorService timer;
  private final UUID publisher;
  private final NotificationLimits limits;
  private final Set<Handshake> handshakes = ConcurrentHashMap.newKeySet();
  private final Object lifecycle = new Object();
  private boolean closing;

  public OkHttpWebSocketTransport(
      URI gatewayUri, ExecutorService senderExecutor, ScheduledExecutorService timer) {
    this(defaultDialer(), gatewayUri, senderExecutor, timer, NotificationLimits.defaults());
  }

  /** 测试构造：注入自定义 dialer、借用执行资源与逻辑 limits。 */
  OkHttpWebSocketTransport(
      WebSocketDialer dialer,
      URI gatewayUri,
      ExecutorService senderExecutor,
      ScheduledExecutorService timer,
      NotificationLimits limits) {
    this.dialer = Objects.requireNonNull(dialer, "dialer");
    this.gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
    this.senderExecutor = Objects.requireNonNull(senderExecutor, "senderExecutor");
    this.timer = Objects.requireNonNull(timer, "timer");
    this.publisher = UUID.randomUUID();
    this.limits = Objects.requireNonNull(limits, "limits");
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
    Handshake handshake = new Handshake(listener);
    // 检查围栏与登记握手在同一 lifecycle gate 内原子完成，close 不会漏掉并发注册的握手。
    synchronized (lifecycle) {
      if (closing) {
        return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
      }
      handshakes.add(handshake);
    }
    return handshake.start();
  }

  /** 在 lifecycle gate 内取快照并围栏，随后在锁外终止每一条握手；不关闭借用的执行器。 */
  @Override
  public void close() {
    List<Handshake> snapshot;
    synchronized (lifecycle) {
      if (closing) {
        return;
      }
      closing = true;
      snapshot = new ArrayList<>(handshakes);
      handshakes.clear();
    }
    for (Handshake handshake : snapshot) {
      handshake.shutdown();
    }
  }

  private boolean isClosing() {
    synchronized (lifecycle) {
      return closing;
    }
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
    private final OkHttpWebSocketConnection connection;
    private final DaemonTransportListener delegate;
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicBoolean rejected = new AtomicBoolean();

    private Handshake(DaemonTransportListener delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.connection =
          new OkHttpWebSocketConnection(
              publisher,
              limits,
              senderExecutor,
              timer,
              delegate::onMessage,
              this::notifyDisconnected);
    }

    private CompletionStage<DaemonConnection> start() {
      // 注册后 gate 可能已关闭：不再发起无意义 dial，并明确结束未交付握手。
      if (isClosing()) {
        notifyDisconnected(new IllegalStateException("transport is closed"));
        return result;
      }
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
      if (disconnected.get() || rejected.get() || isClosing()) {
        // transport 已关闭或本连接已被裁定失败：迟到 socket 必须实际释放，并明确结束未交付握手，
        // 绝不允许裸 return 让 connect future 永久 pending。
        webSocket.close(NORMAL_CLOSURE, null);
        notifyDisconnected(new IllegalStateException("transport closed before open"));
        return;
      }
      connection.attach(webSocket);
      if (!permessageDeflateNegotiated(response)) {
        // 未协商必需的传输压缩：按 RFC 6455 以 1010 关闭，不交付未压缩连接。
        reject(MANDATORY_EXTENSION, "server did not negotiate permessage-deflate");
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
      // 非法片/超限文本/raw JSON 由共享 link 判定并驱动一次确定性关闭。
      connection.onInbound(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      // 当前 Daemon 协议只允许承载 carrier 的 UTF-8 文本帧。
      reject(POLICY_VIOLATION, "binary frames are not supported");
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

    /** transport 关闭时终止本连接：释放预算、关闭 socket，并对未交付连接结束连接阶段。 */
    private void shutdown() {
      IllegalStateException cause = new IllegalStateException("transport is closed");
      connection.terminate(cause, NORMAL_CLOSURE, "transport closed", false);
      notifyDisconnected(cause);
    }

    private boolean permessageDeflateNegotiated(Response response) {
      String extensions = response.header(EXTENSIONS_HEADER);
      return extensions != null && extensions.toLowerCase(Locale.ROOT).contains(PERMESSAGE_DEFLATE);
    }

    /** 确定性拒绝：恰好一次 close 帧、恰好一次断开通知，且不再消费后续帧。 */
    private void reject(int closeCode, String reason) {
      if (!rejected.compareAndSet(false, true)) {
        return;
      }
      IllegalStateException cause = new IllegalStateException(reason);
      // terminate 负责恰好一次 close/cancel 实际释放 socket。
      connection.terminate(cause, closeCode, reason, false);
      notifyDisconnected(cause);
    }

    /** 恰好一次地把断开事件交给使用者；未交付连接同时以异常结束连接阶段。 */
    private void notifyDisconnected(Throwable cause) {
      if (!disconnected.compareAndSet(false, true)) {
        return;
      }
      handshakes.remove(this);
      connection.terminateLocally(cause);
      if (settled.compareAndSet(false, true)) {
        result.completeExceptionally(
            cause == null ? new IllegalStateException("websocket closed before open") : cause);
      }
      delegate.onDisconnected(cause);
    }
  }

  /** OkHttp WebSocket 连接适配：共享 carrier 出站、peer 重组、存活判定与幂等关闭。 */
  static final class OkHttpWebSocketConnection implements DaemonConnection {

    private final NotificationPeerLink link;
    private final ExecutorService senderExecutor;
    private final Consumer<Throwable> disconnectedSink;
    private final Object signal = new Object();
    private final Map<UUID, Pending> inflight = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean closeFrameSent = new AtomicBoolean();
    private volatile WebSocket webSocket;
    private boolean drainScheduled;

    OkHttpWebSocketConnection(
        UUID self,
        NotificationLimits limits,
        ExecutorService senderExecutor,
        ScheduledExecutorService timer,
        Consumer<String> inboundSink,
        Consumer<Throwable> disconnectedSink) {
      this.senderExecutor = Objects.requireNonNull(senderExecutor, "senderExecutor");
      this.disconnectedSink = Objects.requireNonNull(disconnectedSink, "disconnectedSink");
      this.link =
          new NotificationPeerLink(
              self,
              NotificationPeerLink.DAEMON_TOPIC,
              limits,
              Objects.requireNonNull(timer, "timer"),
              inboundSink,
              () ->
                  terminate(
                      new IllegalStateException("daemon websocket carrier violation"),
                      POLICY_VIOLATION,
                      "carrier violation",
                      true));
    }

    void attach(WebSocket webSocket) {
      this.webSocket = webSocket;
    }

    void markOpen() {
      open.set(true);
    }

    /** 投递一帧物理文本；非法帧由共享 link 判定并触发一次确定性关闭。 */
    void onInbound(String text) {
      if (stopped.get()) {
        return;
      }
      link.accept(text);
    }

    @Override
    public CompletionStage<Void> sendText(String message) {
      Objects.requireNonNull(message, "message");
      CompletableFuture<Void> future = new CompletableFuture<>();
      boolean accepted = false;
      boolean needDrain = false;
      synchronized (signal) {
        if (!stopped.get() && isOpen()) {
          UUID messageId = UUID.randomUUID();
          Pending pending = new Pending(future);
          // 先注册再入队：drain 永远不会完成一个尚未登记的包。
          inflight.put(messageId, pending);
          accepted = link.offer(messageId, message);
          if (!accepted) {
            inflight.remove(messageId, pending);
          } else if (!drainScheduled) {
            drainScheduled = true;
            needDrain = true;
          }
        }
      }
      if (!accepted) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("websocket rejected the outbound packet"));
      }
      if (needDrain) {
        submitDrain();
      }
      return future;
    }

    @Override
    public void close() {
      terminate(
          new IllegalStateException("websocket closed locally"),
          NORMAL_CLOSURE,
          "daemon stopped",
          false);
    }

    @Override
    public boolean isOpen() {
      return open.get() && !stopped.get();
    }

    /** 断开通知到达时释放预算，但不重复发送 close 帧或通知。 */
    void terminateLocally(Throwable cause) {
      if (!stopped.compareAndSet(false, true)) {
        return;
      }
      open.set(false);
      link.close();
      failDrainSchedule();
      failInflight(cause);
    }

    private void terminate(Throwable cause, int closeCode, String reason, boolean notify) {
      if (!stopped.compareAndSet(false, true)) {
        return;
      }
      open.set(false);
      link.close();
      failDrainSchedule();
      closeSocket(closeCode, reason);
      failInflight(cause);
      if (notify) {
        disconnectedSink.accept(cause);
      }
    }

    /** 提交 drain；被 executor 拒绝时明确失败并真实释放 socket，绝不重试。 */
    private void submitDrain() {
      try {
        senderExecutor.execute(this::drainTask);
      } catch (RuntimeException rejected) {
        terminate(
            new IllegalStateException("daemon sender executor rejected the drain task", rejected),
            -1,
            null,
            true);
      }
    }

    /**
     * 单个 drain 任务至多发送共享 outbox 的一个 {@code sendBatchFrames} 批；native 非阻塞 send 逐片复核围栏，末片接受后于锁外完成整包
     * future，再在 {@code signal} 下决定是否调度下一批，保证调用线程不内联跑完整包且不丢唤醒。
     */
    private void drainTask() {
      NotificationOutbox.Batch batch;
      synchronized (signal) {
        if (stopped.get()) {
          drainScheduled = false;
          return;
        }
        batch = link.pollBatch().orElse(null);
        if (batch == null) {
          drainScheduled = false;
          return;
        }
      }
      Pending done = null;
      boolean failed = false;
      try {
        WebSocket current = webSocket;
        for (String frame : batch.frames()) {
          // 关闭/失败后绝不继续整批 native write。
          if (stopped.get() || !isOpen() || current == null) {
            throw new IllegalStateException("daemon WebSocket is closing");
          }
          if (!current.send(frame)) {
            throw new IllegalStateException("websocket rejected the outbound frame");
          }
        }
        link.complete(batch, true);
        done = acknowledge(batch);
      } catch (RuntimeException error) {
        link.complete(batch, false);
        failed = true;
        terminate(error, -1, null, true);
      }
      if (done != null) {
        // future 在锁外完成，允许调用方回调重入。
        done.future.complete(null);
      }
      if (!failed) {
        continueOrIdle();
      }
    }

    /** 在 {@code signal} 下重新判定：仍有 pending 则保持已调度标记并再排一批，否则清标记进入空闲。 */
    private void continueOrIdle() {
      boolean resubmit = false;
      synchronized (signal) {
        if (!stopped.get() && link.hasPending()) {
          resubmit = true;
        } else {
          drainScheduled = false;
        }
      }
      if (resubmit) {
        submitDrain();
      }
    }

    private Pending acknowledge(NotificationOutbox.Batch batch) {
      synchronized (signal) {
        Pending pending = inflight.get(batch.messageId());
        if (pending == null) {
          return null;
        }
        if (pending.written.addAndGet(batch.frameCount())
            >= NotificationCarrier.count(batch.totalBytes())) {
          inflight.remove(batch.messageId(), pending);
          return pending;
        }
        return null;
      }
    }

    private void failDrainSchedule() {
      synchronized (signal) {
        drainScheduled = false;
      }
    }

    private void failInflight(Throwable cause) {
      List<Pending> failing;
      synchronized (signal) {
        failing = new ArrayList<>(inflight.values());
        inflight.clear();
      }
      for (Pending pending : failing) {
        pending.future.completeExceptionally(cause);
      }
    }

    /** native 发送失败必须实际释放 socket：有 close code 时发一次 close，否则立即 cancel。 */
    private void closeSocket(int closeCode, String reason) {
      WebSocket current = webSocket;
      if (current == null || !closeFrameSent.compareAndSet(false, true)) {
        return;
      }
      if (closeCode >= 0) {
        current.close(closeCode, reason);
      } else {
        current.cancel();
      }
    }
  }

  private static final class Pending {
    final CompletableFuture<Void> future;
    final AtomicInteger written = new AtomicInteger();

    Pending(CompletableFuture<Void> future) {
      this.future = future;
    }
  }
}
