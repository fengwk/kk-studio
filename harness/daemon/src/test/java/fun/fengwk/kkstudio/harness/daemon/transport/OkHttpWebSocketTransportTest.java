package fun.fengwk.kkstudio.harness.daemon.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationPacket;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** OkHttp transport 适配器必须保留握手协商门禁、共享 carrier 成帧与 close/disconnect 所有权。 */
class OkHttpWebSocketTransportTest {

  private static final URI GATEWAY = URI.create("ws://127.0.0.1:1/daemon");
  private static final String DEFLATE = "permessage-deflate";
  private static final String SENDER_NAME = "test-sender";

  /** 借用给 transport 的单线程 sender：断言 native write 只在该线程发生。 */
  private final ExecutorService sender =
      Executors.newSingleThreadExecutor(r -> new Thread(r, SENDER_NAME));

  /** 借用给 transport 的到期 timer：断言缺片在无后续输入时也会到期关闭。 */
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void shutdownBorrowedResources() {
    sender.shutdownNow();
    timer.shutdownNow();
  }

  /** 连接适配器把逻辑消息成 carrier 帧发送、拒绝已关闭 socket，并发送一次 graceful close。 */
  @Test
  void adaptsSendAndCloseLifecycle() throws Exception {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;

    connection.sendText("hello").toCompletableFuture().join();
    await(() -> webSocket.textMessages.size() == 1);
    assertEquals("hello", decode(webSocket.textMessages.get(0)));
    assertTrue(connection.isOpen());

    connection.close();
    connection.close();
    await(() -> webSocket.closeCalls.get() == 1);
    assertEquals(OkHttpWebSocketTransport.NORMAL_CLOSURE, webSocket.closeStatus);
    assertFalse(connection.isOpen());
    assertTrue(connection.sendText("after-close").toCompletableFuture().isCompletedExceptionally());
  }

  /** native write 只能发生在借用的 sender 线程：sendText 调用线程绝不内联发完整个包。 */
  @Test
  void nativeWriteRunsOnBorrowedSenderThreadNotCaller() throws Exception {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;

    connection.sendText("payload").toCompletableFuture().join();

    assertNotNull(webSocket.sendThread);
    assertNotSame(Thread.currentThread(), webSocket.sendThread);
    assertEquals(SENDER_NAME, webSocket.sendThread.getName());
  }

  /** 未 attach 的连接不能发送，也必须能在 close 时安全空转。 */
  @Test
  void rejectsSendBeforeAttach() {
    OkHttpWebSocketTransport.OkHttpWebSocketConnection connection =
        new OkHttpWebSocketTransport.OkHttpWebSocketConnection(
            UUID.randomUUID(),
            NotificationLimits.defaults(),
            sender,
            timer,
            body -> {},
            error -> {});

    assertFalse(connection.isOpen());
    assertTrue(connection.sendText("early").toCompletableFuture().isCompletedExceptionally());
    connection.close();
    assertFalse(connection.isOpen());
  }

  /** 服务端协商成功时连接被交付，承载后完整重组后续入站逻辑消息。 */
  @Test
  void deliversConnectionWhenPermessageDeflateIsNegotiated() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;

    assertTrue(connection.isOpen());
    assertEquals(0, webSocket.closeCalls.get());

    dialer.listener.onMessage(webSocket, carrier("{\"a\":1}"));
    assertEquals(List.of("{\"a\":1}"), listener.messages);
    assertEquals(0, listener.disconnections.get());
  }

  /** 服务端未协商必需扩展时：以 1010 关闭、连接阶段失败、断开通知一次，且不投递后续消息。 */
  @Test
  void rejectsConnectionWithoutPermessageDeflate() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = transport(dialer).connect(listener);

    FakeWebSocket webSocket = new FakeWebSocket();
    dialer.listener.onOpen(webSocket, response(null));

    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(OkHttpWebSocketTransport.MANDATORY_EXTENSION, webSocket.closeStatus);
    assertEquals(1, listener.disconnections.get());
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());

    dialer.listener.onMessage(webSocket, carrier("late"));
    assertTrue(listener.messages.isEmpty());
  }

  /** 非法片（超出物理上限）确定性拒绝：一次 1008 close、无消息投递、不再消费后续帧。 */
  @Test
  void rejectsOversizedFrameAndStopsConsumingFrames() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;

    dialer.listener.onMessage(webSocket, "x".repeat(NotificationCarrier.PAYLOAD_LIMIT));
    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(OkHttpWebSocketTransport.POLICY_VIOLATION, webSocket.closeStatus);
    assertTrue(listener.messages.isEmpty());
    assertEquals(1, listener.disconnections.get());

    dialer.listener.onMessage(webSocket, carrier("late"));
    assertTrue(listener.messages.isEmpty());
  }

  /** raw JSON 物理旁路已删除：未承载的文本帧同样被确定性拒绝。 */
  @Test
  void rejectsLegacyRawJsonFrame() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;

    dialer.listener.onMessage(webSocket, "{\"messageType\":\"WELCOME\"}");

    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(OkHttpWebSocketTransport.POLICY_VIOLATION, webSocket.closeStatus);
    assertTrue(listener.messages.isEmpty());
    assertEquals(1, listener.disconnections.get());
  }

  /** binary 帧必须确定性拒绝为 1008。 */
  @Test
  void rejectsBinaryFrames() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;
    dialer.listener.onMessage(webSocket, ByteString.of((byte) 1, (byte) 2));

    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(OkHttpWebSocketTransport.POLICY_VIOLATION, webSocket.closeStatus);
    assertTrue(listener.messages.isEmpty());
    assertEquals(1, listener.disconnections.get());
  }

  /** 缺片在没有后续入站输入时也必须由注入 timer 到期关闭，并释放预算。 */
  @Test
  void expiresPartialMessageWithoutFurtherInput() throws Exception {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener, fastLimits());
    FakeWebSocket webSocket = dialer.webSocket;

    // 只投递多片消息的首片：reassembler 一直缺片，禁止无后续输入就永久驻留。
    dialer.listener.onMessage(webSocket, carrier("y".repeat(20_000)));

    await(() -> webSocket.closeCalls.get() == 1);
    assertEquals(OkHttpWebSocketTransport.POLICY_VIOLATION, webSocket.closeStatus);
    assertTrue(listener.messages.isEmpty());
    assertEquals(1, listener.disconnections.get());
    assertFalse(connection.isOpen());
  }

  /** close 与 failure 是竞争的终态通知，对 runtime 只能通知一次，并保留首个 cause。 */
  @Test
  void notifiesDisconnectOnlyOnce() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener);

    FakeWebSocket webSocket = dialer.webSocket;
    IllegalStateException error = new IllegalStateException("network");
    dialer.listener.onFailure(webSocket, error, null);
    dialer.listener.onClosed(webSocket, OkHttpWebSocketTransport.NORMAL_CLOSURE, "closed");

    assertEquals(1, listener.disconnections.get());
    assertSame(error, listener.cause);
    assertFalse(connection.isOpen());
  }

  /** 对端在 open 前失败时，连接阶段必须以异常结束且不泄漏连接；已交付连接断开时只通知 runtime。 */
  @Test
  void failsConnectBeforeOpenAndNotifiesDeliveredConnections() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> failedStage = transport(dialer).connect(listener);

    IllegalStateException refused = new IllegalStateException("refused");
    dialer.listener.onFailure(new FakeWebSocket(), refused, null);
    assertThrows(CompletionException.class, () -> failedStage.toCompletableFuture().join());
    assertEquals(1, listener.disconnections.get());

    RecordingListener second = new RecordingListener();
    DaemonConnection connection = open(dialer, second);

    dialer.listener.onClosed(dialer.webSocket, 1001, "bye");
    assertEquals(1, second.disconnections.get());
    assertFalse(connection.isOpen());
  }

  /** 非法构造参数（含借用资源）在构造期拒绝。 */
  @Test
  void rejectsInvalidConstructorArguments() {
    FakeDialer dialer = new FakeDialer();
    NotificationLimits limits = NotificationLimits.defaults();
    assertThrows(
        NullPointerException.class,
        () -> new OkHttpWebSocketTransport(null, GATEWAY, sender, timer, limits));
    assertThrows(
        NullPointerException.class,
        () -> new OkHttpWebSocketTransport(dialer, null, sender, timer, limits));
    assertThrows(
        NullPointerException.class,
        () -> new OkHttpWebSocketTransport(dialer, GATEWAY, null, timer, limits));
    assertThrows(
        NullPointerException.class,
        () -> new OkHttpWebSocketTransport(dialer, GATEWAY, sender, null, limits));
    assertThrows(
        NullPointerException.class,
        () -> new OkHttpWebSocketTransport(dialer, GATEWAY, sender, timer, null));
  }

  /** 公共构造器与真实 OkHttp connect 装配：对不可达地址的握手必须异步失败而不是同步抛出。 */
  @Test
  void publicConstructorsAndRealConnectWiring() {
    URI unreachable = URI.create("ws://127.0.0.1:59999/");
    OkHttpWebSocketTransport defaultTransport =
        new OkHttpWebSocketTransport(unreachable, sender, timer);

    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = defaultTransport.connect(listener);
    assertNotNull(stage);
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    defaultTransport.close();
  }

  /** 并发多 sender 入队：注册先于入队且不丢唤醒，每个包的 future 都必须完成而不是挂起。 */
  @Test
  void concurrentSendersAlwaysCompleteTheirFutures() throws Exception {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener);

    int count = 200;
    List<CompletionStage<Void>> stages = new CopyOnWriteArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String body = "m" + i;
      Thread thread = Thread.ofVirtual().start(() -> stages.add(connection.sendText(body)));
      threads.add(thread);
    }
    for (Thread thread : threads) {
      thread.join();
    }
    await(() -> stages.size() == count && dialer.webSocket.textMessages.size() == count);
    for (CompletionStage<Void> stage : stages) {
      stage.toCompletableFuture().join();
      assertFalse(stage.toCompletableFuture().isCompletedExceptionally());
    }
    assertEquals(count, dialer.webSocket.textMessages.size());
  }

  /** native send 返回 false 时必须真实释放 socket，并让整包 future 异常完成，绝不重试。 */
  @Test
  void sendFailureReleasesSocketAndFailsFuture() throws Exception {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;
    webSocket.rejectSend = true;

    CompletionStage<Void> stage = connection.sendText("boom");

    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertEquals(1, webSocket.cancelCalls.get());
    assertEquals(1, listener.disconnections.get());
    assertFalse(connection.isOpen());
  }

  /** transport.close 围栏新 connect、终止在途连接并关闭其 socket；迟到 connect 立即失败。 */
  @Test
  void closeTerminatesActiveConnectionAndFencesNewConnects() throws Exception {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    DaemonConnection connection = open(transport, dialer, listener);

    transport.close();

    assertFalse(connection.isOpen());
    assertEquals(1, listener.disconnections.get());
    await(() -> dialer.webSocket.closeCalls.get() == 1);

    CompletionStage<DaemonConnection> late = transport.connect(new RecordingListener());
    assertThrows(CompletionException.class, () -> late.toCompletableFuture().join());
  }

  /** transport.close 之后迟到的 onOpen 必须真实关闭该 socket 且不交付连接。 */
  @Test
  void closesLateSocketAfterTransportClose() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    transport.connect(listener);

    transport.close();

    FakeWebSocket lateSocket = new FakeWebSocket();
    dialer.listener.onOpen(lateSocket, response(DEFLATE));

    assertEquals(1, lateSocket.closeCalls.get());
    assertTrue(listener.messages.isEmpty());
  }

  /** 握手失败后迟到的 onOpen 必须实际关闭该 socket 且不交付连接。 */
  @Test
  void closesLateSocketAfterFailure() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = transport(dialer).connect(listener);

    dialer.listener.onFailure(new FakeWebSocket(), new IllegalStateException("refused"), null);
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());

    FakeWebSocket lateSocket = new FakeWebSocket();
    dialer.listener.onOpen(lateSocket, response(DEFLATE));

    assertEquals(1, lateSocket.closeCalls.get());
    assertTrue(listener.messages.isEmpty());
  }

  private DaemonConnection open(FakeDialer dialer, RecordingListener listener) {
    return open(transport(dialer), dialer, listener);
  }

  private DaemonConnection open(
      FakeDialer dialer, RecordingListener listener, NotificationLimits limits) {
    return open(transport(dialer, limits), dialer, listener);
  }

  private static DaemonConnection open(
      OkHttpWebSocketTransport transport, FakeDialer dialer, RecordingListener listener) {
    CompletionStage<DaemonConnection> stage = transport.connect(listener);
    dialer.webSocket = new FakeWebSocket();
    dialer.listener.onOpen(dialer.webSocket, response(DEFLATE));
    return stage.toCompletableFuture().join();
  }

  private OkHttpWebSocketTransport transport(FakeDialer dialer) {
    return transport(dialer, NotificationLimits.defaults());
  }

  private OkHttpWebSocketTransport transport(FakeDialer dialer, NotificationLimits limits) {
    return new OkHttpWebSocketTransport(dialer, GATEWAY, sender, timer, limits);
  }

  /** 缩短重组到期，使缺片测试无需等待默认 5s。 */
  private static NotificationLimits fastLimits() {
    return new NotificationLimits(
        8 * 1024 * 1024, 32 * 1024 * 1024, 256, 32 * 1024 * 1024, 8, Duration.ofMillis(20), 32);
  }

  /** 构造一帧由随机 peer 发布的广播 carrier，承载给定逻辑体。 */
  private static String carrier(String body) {
    return NotificationCarrier.chunk(
            new NotificationPacket(
                UUID.randomUUID(),
                null,
                NotificationPeerLink.DAEMON_TOPIC,
                UUID.randomUUID(),
                body.getBytes(StandardCharsets.UTF_8)),
            0)
        .encode();
  }

  private static String decode(String frame) {
    return new String(
        NotificationCarrier.decode(frame, NotificationLimits.defaults(), UUID.randomUUID()).bytes(),
        StandardCharsets.UTF_8);
  }

  private static Response response(String extensions) {
    Response.Builder builder =
        new Response.Builder()
            .request(new Request.Builder().url(GATEWAY.toASCIIString()).build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols");
    if (extensions != null) {
      builder.header(OkHttpWebSocketTransport.EXTENSIONS_HEADER, extensions);
    }
    return builder.build();
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!check.get() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(check.get());
  }

  @FunctionalInterface
  private interface Check {
    boolean get();
  }

  /** 捕获 upgrade 请求与 listener，让测试完全掌控握手与入站帧时序。 */
  private static final class FakeDialer implements OkHttpWebSocketTransport.WebSocketDialer {

    private final List<Request> requests = new ArrayList<>();
    private WebSocketListener listener;
    private FakeWebSocket webSocket;

    @Override
    public void dial(Request request, WebSocketListener listener) {
      requests.add(request);
      this.listener = listener;
    }
  }

  private static final class RecordingListener implements DaemonTransportListener {

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final AtomicInteger disconnections = new AtomicInteger();
    private volatile Throwable cause;

    @Override
    public void onMessage(String message) {
      messages.add(message);
    }

    @Override
    public void onDisconnected(Throwable cause) {
      this.cause = cause;
      disconnections.incrementAndGet();
    }
  }

  private static final class FakeWebSocket implements WebSocket {

    private final List<String> textMessages = new CopyOnWriteArrayList<>();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private volatile int closeStatus;
    private volatile Thread sendThread;
    private volatile boolean rejectSend;

    @Override
    public Request request() {
      return new Request.Builder().url(GATEWAY.toASCIIString()).build();
    }

    @Override
    public long queueSize() {
      return 0;
    }

    @Override
    public boolean send(String text) {
      sendThread = Thread.currentThread();
      if (rejectSend) {
        return false;
      }
      textMessages.add(text);
      return true;
    }

    @Override
    public boolean send(ByteString bytes) {
      return false;
    }

    @Override
    public boolean close(int code, String reason) {
      closeStatus = code;
      closeCalls.incrementAndGet();
      return true;
    }

    @Override
    public void cancel() {
      cancelCalls.incrementAndGet();
    }
  }
}
