package fun.fengwk.kkstudio.harness.daemon.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** OkHttp transport 适配器必须保留握手协商门禁、完整文本成帧以及 close/disconnect 所有权。 */
class OkHttpWebSocketTransportTest {

  private static final URI GATEWAY = URI.create("ws://127.0.0.1:1/daemon");
  private static final String DEFLATE = "permessage-deflate";

  /** 连接适配器发送完整帧，拒绝已关闭 socket，并发送一次 graceful close。 */
  @Test
  void adaptsSendAndCloseLifecycle() {
    FakeWebSocket webSocket = new FakeWebSocket();
    OkHttpWebSocketTransport.OkHttpWebSocketConnection connection =
        new OkHttpWebSocketTransport.OkHttpWebSocketConnection();
    connection.attach(webSocket);
    connection.markOpen();

    connection.sendText("hello").toCompletableFuture().join();
    assertEquals(List.of("hello"), webSocket.textMessages);
    assertTrue(connection.isOpen());

    connection.close();
    connection.close();
    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(OkHttpWebSocketTransport.NORMAL_CLOSURE, webSocket.closeStatus);
    assertFalse(connection.isOpen());
    assertTrue(connection.sendText("after-close").toCompletableFuture().isCompletedExceptionally());
  }

  /** 未 attach 的适配器不能发送，也必须能在 close 时安全空转。 */
  @Test
  void rejectsSendBeforeAttach() {
    OkHttpWebSocketTransport.OkHttpWebSocketConnection connection =
        new OkHttpWebSocketTransport.OkHttpWebSocketConnection();

    assertFalse(connection.isOpen());
    assertTrue(connection.sendText("early").toCompletableFuture().isCompletedExceptionally());
    connection.close();
    assertFalse(connection.isOpen());
  }

  /** 服务端协商成功时连接被交付，后续入站文本按完整消息投递。 */
  @Test
  void deliversConnectionWhenPermessageDeflateIsNegotiated() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    DaemonConnection connection = open(dialer, listener);
    FakeWebSocket webSocket = dialer.webSocket;

    assertTrue(connection.isOpen());
    assertEquals(0, webSocket.closeCalls.get());

    dialer.listener.onMessage(webSocket, "{\"a\":1}");
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

    dialer.listener.onMessage(webSocket, "late");
    assertTrue(listener.messages.isEmpty());
  }

  /** 协商参数中不包含 permessage-deflate token 时同样拒绝，避免把其它扩展误判为压缩。 */
  @Test
  void rejectsConnectionWhenOnlyUnrelatedExtensionsAreNegotiated() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = transport(dialer).connect(listener);

    FakeWebSocket webSocket = new FakeWebSocket();
    dialer.listener.onOpen(webSocket, response("x-custom-extension"));

    assertEquals(OkHttpWebSocketTransport.MANDATORY_EXTENSION, webSocket.closeStatus);
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertEquals(1, listener.disconnections.get());
  }

  /** 单条入站文本超过上限时确定性拒绝：一次 1008 close、无消息投递、不再消费后续帧。 */
  @Test
  void rejectsOversizedTextAndStopsConsumingFrames() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    open(dialer, listener, 5);
    FakeWebSocket webSocket = dialer.webSocket;

    dialer.listener.onMessage(webSocket, "abcdef");
    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(OkHttpWebSocketTransport.POLICY_VIOLATION, webSocket.closeStatus);
    assertTrue(listener.messages.isEmpty());
    assertEquals(1, listener.disconnections.get());

    dialer.listener.onMessage(webSocket, "g");
    assertEquals(1, webSocket.closeCalls.get());
    assertTrue(listener.messages.isEmpty());
  }

  /** 恰好等于上限的完整消息正常投递。 */
  @Test
  void acceptsMessagesAtTheSizeLimit() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    open(dialer, listener, 5);
    dialer.listener.onMessage(dialer.webSocket, "abcde");

    assertEquals(List.of("abcde"), listener.messages);
    assertEquals(0, dialer.webSocket.closeCalls.get());
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

  /** 非法上限在构造期拒绝。 */
  @Test
  void rejectsInvalidConstructorArguments() {
    FakeDialer dialer = new FakeDialer();
    assertThrows(
        IllegalArgumentException.class, () -> new OkHttpWebSocketTransport(dialer, GATEWAY, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new OkHttpWebSocketTransport(dialer, GATEWAY, -1));
  }

  /** 公共构造器与真实 OkHttp connect 装配：对不可达地址的握手必须异步失败而不是同步抛出。 */
  @Test
  void publicConstructorsAndRealConnectWiring() {
    URI unreachable = URI.create("ws://127.0.0.1:59999/");
    OkHttpWebSocketTransport defaultTransport = new OkHttpWebSocketTransport(unreachable);
    OkHttpWebSocketTransport boundedTransport = new OkHttpWebSocketTransport(unreachable, 1024);

    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = boundedTransport.connect(listener);
    assertNotNull(stage);
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertNotNull(defaultTransport.connect(listener));
    defaultTransport.close();
  }

  /** 完成一次成功握手并返回交付的连接，逼近生产时序：先 dial，再收到 101 + 扩展协商。 */
  private static DaemonConnection open(FakeDialer dialer, RecordingListener listener) {
    return open(dialer, listener, OkHttpWebSocketTransport.DEFAULT_MAX_INBOUND_TEXT_CHARS);
  }

  private static DaemonConnection open(
      FakeDialer dialer, RecordingListener listener, int maxInboundTextChars) {
    CompletionStage<DaemonConnection> stage =
        transport(dialer, maxInboundTextChars).connect(listener);
    dialer.webSocket = new FakeWebSocket();
    dialer.listener.onOpen(dialer.webSocket, response(DEFLATE));
    return stage.toCompletableFuture().join();
  }

  private static OkHttpWebSocketTransport transport(FakeDialer dialer) {
    return transport(dialer, OkHttpWebSocketTransport.DEFAULT_MAX_INBOUND_TEXT_CHARS);
  }

  private static OkHttpWebSocketTransport transport(FakeDialer dialer, int maxInboundTextChars) {
    return new OkHttpWebSocketTransport(dialer, GATEWAY, maxInboundTextChars);
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

    private final List<String> messages = new ArrayList<>();
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

    private final List<String> textMessages = new ArrayList<>();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private volatile int closeStatus;

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
    public void cancel() {}
  }
}
