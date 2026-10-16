package fun.fengwk.kkstudio.harness.daemon.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** OkHttp transport 适配器必须保留握手协商门禁、完整文本成帧以及 close/disconnect 所有权。 */
class OkHttpWebSocketTransportTest {

  private static final URI GATEWAY = URI.create("ws://127.0.0.1:1/daemon");
  private static final String DEFLATE = "permessage-deflate";

  /** 与生产契约一致的握手预算（测试不引用生产私有常量）。 */
  private static final long HANDSHAKE_BUDGET_MILLIS = 2000;

  /** 真实线程与线程池收敛的等待上限。 */
  private static final long THREAD_JOIN_MILLIS = 5000;

  /** 连接适配器发送完整帧，拒绝已关闭 socket，并发送一次 graceful close。 */
  @Test
  void adaptsSendAndCloseLifecycle() {
    FakeWebSocket webSocket = new FakeWebSocket();
    OkHttpWebSocketTransport.OkHttpWebSocketConnection connection =
        new OkHttpWebSocketTransport.OkHttpWebSocketConnection();
    connection.attach(webSocket);
    assertTrue(connection.markOpen());

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

    FakeWebSocket webSocket = dialer.webSocket;
    dialer.listener.onOpen(webSocket, response(null));

    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(0, webSocket.cancelCalls.get());
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

    dialer.listener.onOpen(dialer.webSocket, response("x-custom-extension"));

    assertEquals(OkHttpWebSocketTransport.MANDATORY_EXTENSION, dialer.webSocket.closeStatus);
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertEquals(1, listener.disconnections.get());
  }

  /** 形如 {@code not-permessage-deflate} 的扩展名不得被子串匹配误判为已协商压缩。 */
  @Test
  void rejectsExtensionWhoseTokenOnlyContainsTheDeflateName() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = transport(dialer).connect(listener);

    dialer.listener.onOpen(dialer.webSocket, response("not-permessage-deflate"));

    assertEquals(OkHttpWebSocketTransport.MANDATORY_EXTENSION, dialer.webSocket.closeStatus);
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertEquals(1, listener.disconnections.get());
  }

  /** 逗号分组、大小写不同但名称 token 相同时视为协商成功。 */
  @Test
  void acceptsDeflateTokenWithParametersAndDifferentCase() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = transport(dialer).connect(listener);

    dialer.listener.onOpen(dialer.webSocket, response("x-custom; a=1, PerMessage-Deflate; b=2"));

    DaemonConnection connection = stage.toCompletableFuture().join();
    assertTrue(connection.isOpen());
    assertEquals(0, listener.disconnections.get());
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

  /** dial 返回前同步回调 onOpen 时：仍恰好交付一次，close 幂等。 */
  @Test
  void deliversWhenOnOpenArrivesBeforeDialReturns() {
    FakeDialer dialer = new FakeDialer();
    dialer.synchronousOpen = true;
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);

    DaemonConnection connection = transport.connect(listener).toCompletableFuture().join();

    assertTrue(connection.isOpen());
    assertEquals(0, listener.disconnections.get());
    connection.close();
    connection.close();
    assertEquals(1, dialer.webSocket.closeCalls.get());
    transport.close();
  }

  /** 未交付握手在 transport close 时结算：future 失败、原生句柄取消、断开通知一次、dialer 释放。 */
  @Test
  void closesPendingHandshakeAndReleasesClientOnTransportClose() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    CompletableFuture<DaemonConnection> stage = transport.connect(listener).toCompletableFuture();

    transport.close();
    transport.close();

    assertTrue(dialer.closed.get());
    assertTrue(stage.isCompletedExceptionally());
    assertEquals(1, listener.disconnections.get());
    assertEquals(1, dialer.webSocket.cancelCalls.get());
  }

  /** close 时单个使用者的断开回调失败不得跳过其余握手与 dialer 释放，并保留首个失败。 */
  @Test
  void continuesClosingRemainingHandshakesWhenOneCallbackFails() {
    FakeDialer dialer = new FakeDialer();
    OkHttpWebSocketTransport transport = transport(dialer);
    IllegalStateException boom = new IllegalStateException("listener failed");
    CompletableFuture<DaemonConnection> first =
        transport.connect(new FailingListener(boom)).toCompletableFuture();
    RecordingListener second = new RecordingListener();
    CompletableFuture<DaemonConnection> secondStage =
        transport.connect(second).toCompletableFuture();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, transport::close);

    assertSame(boom, thrown);
    assertTrue(dialer.closed.get());
    assertTrue(first.isCompletedExceptionally());
    assertTrue(secondStage.isCompletedExceptionally());
    assertEquals(1, second.disconnections.get());
  }

  /** dialer 释放失败必须向外传播，且此时握手已被收敛。 */
  @Test
  void propagatesDialerCloseFailureAfterSettlingHandshakes() {
    FakeDialer dialer = new FakeDialer();
    dialer.closeFailure = new IllegalStateException("client release failed");
    OkHttpWebSocketTransport transport = transport(dialer);
    CompletableFuture<DaemonConnection> stage =
        transport.connect(new RecordingListener()).toCompletableFuture();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, transport::close);

    assertSame(dialer.closeFailure, thrown);
    assertTrue(stage.isCompletedExceptionally());
    assertEquals(1, dialer.webSocket.cancelCalls.get());
  }

  /** 断开回调抛 Error、随后取消原生句柄抛 runtime 时：Error 仍是主因，后续失败只作为 suppressed。 */
  @Test
  void keepsCallbackErrorWhenCancelAlsoFails() {
    FakeDialer dialer = new FakeDialer();
    Error callbackError = new AssertionError("listener exploded");
    RuntimeException cancelError = new IllegalStateException("cancel failed");
    dialer.cancelFailure = cancelError;
    OkHttpWebSocketTransport transport = transport(dialer);
    CompletableFuture<DaemonConnection> stage =
        transport.connect(new FailingErrorListener(callbackError)).toCompletableFuture();

    Throwable thrown = assertThrows(Error.class, transport::close);

    assertSame(callbackError, thrown);
    assertArrayEquals(new Throwable[] {cancelError}, thrown.getSuppressed());
    assertTrue(stage.isCompletedExceptionally());
    assertTrue(dialer.closed.get());
  }

  /** 首个失败是 runtime、dialer 释放再抛 Error 时：Error 提升为主因，原 runtime 变为 suppressed 且未被跳过。 */
  @Test
  void promotesDialerErrorOverEarlierCallbackRuntime() {
    FakeDialer dialer = new FakeDialer();
    RuntimeException callbackError = new IllegalStateException("listener failed");
    Error releaseError = new AssertionError("client release failed");
    dialer.closeFailure = releaseError;
    OkHttpWebSocketTransport transport = transport(dialer);
    CompletableFuture<DaemonConnection> stage =
        transport.connect(new FailingListener(callbackError)).toCompletableFuture();

    Throwable thrown = assertThrows(Error.class, transport::close);

    assertSame(releaseError, thrown);
    assertArrayEquals(new Throwable[] {callbackError}, thrown.getSuppressed());
    assertTrue(stage.isCompletedExceptionally());
    assertTrue(dialer.closed.get());
    assertEquals(1, dialer.webSocket.cancelCalls.get());
  }

  /** 多个失败并存时仍必须全部收敛：每个 future 结算、每个原生句柄取消一次、dialer 释放且 suppressed 精确。 */
  @Test
  void settlesEveryHandshakeAndHandleDespiteMultipleFailures() {
    FakeDialer dialer = new FakeDialer();
    RuntimeException callbackError = new IllegalStateException("listener failed");
    Error releaseError = new AssertionError("client release failed");
    dialer.closeFailure = releaseError;
    OkHttpWebSocketTransport transport = transport(dialer);
    CompletableFuture<DaemonConnection> failed =
        transport.connect(new FailingListener(callbackError)).toCompletableFuture();
    RecordingListener settledListener = new RecordingListener();
    CompletableFuture<DaemonConnection> settled =
        transport.connect(settledListener).toCompletableFuture();

    Throwable thrown = assertThrows(Error.class, transport::close);

    assertSame(releaseError, thrown);
    assertArrayEquals(new Throwable[] {callbackError}, thrown.getSuppressed());
    assertTrue(failed.isCompletedExceptionally());
    assertTrue(settled.isCompletedExceptionally());
    assertEquals(1, settledListener.disconnections.get());
    assertEquals(2, dialer.sockets.size());
    for (FakeWebSocket socket : dialer.sockets) {
      assertEquals(1, socket.cancelCalls.get());
    }
    assertTrue(dialer.closed.get());
  }

  /**
   * close 与 dial 返回的真实竞争：connect 调用线程阻塞在 dial 内，close 在原生句柄创建前到达，必须立即结算 pending
   * future；此后迟到的原生句柄仍必须被取消，不得泄漏。
   */
  @Test
  void closesHandshakeWhileDialIsBlockedAndCancelsLateHandle() throws Exception {
    FakeDialer dialer = new FakeDialer();
    dialer.blockDial(new CountDownLatch(1));
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    AtomicReference<CompletionStage<DaemonConnection>> stageRef = new AtomicReference<>();
    Thread connector =
        new Thread(() -> stageRef.set(transport.connect(listener)), "dial-return-race");
    connector.start();
    try {
      assertTrue(dialer.awaitDialEntered(THREAD_JOIN_MILLIS), "dial 应已进入阻塞");
      assertNull(dialer.webSocket, "dial 尚未返回就不应存在原生句柄");

      transport.close();
    } finally {
      dialer.releaseDial();
      connector.join(THREAD_JOIN_MILLIS);
    }

    assertFalse(connector.isAlive(), "connect 调用线程应已返回");
    CompletionStage<DaemonConnection> stage = stageRef.get();
    assertNotNull(stage, "connect 应返回失败的 stage 而不是抛出");
    assertTrue(stage.toCompletableFuture().isCompletedExceptionally());
    assertEquals(1, listener.disconnections.get());
    assertEquals(1, dialer.webSocket.cancelCalls.get());
    assertTrue(dialer.closed.get());
  }

  /**
   * 仅靠 callTimeout 不足以约束握手：JDK 同步 DNS 是 callTimeout 无法强制中断的物理边界，只要解析仍在阻塞，就必须由逻辑 future
   * 期限有界结算。本用例不宣称 close 能立即终止原生 DNS，只断言逻辑期限。
   */
  @Test
  void logicalDeadlineSettlesHandshakeWhileDnsBlocksTheCallTimeout() throws Exception {
    CountDownLatch dnsEntered = new CountDownLatch(1);
    CountDownLatch releaseDns = new CountDownLatch(1);
    OkHttpClient client =
        new OkHttpClient.Builder()
            .dns(new BlockingDns(dnsEntered, releaseDns))
            .connectTimeout(Duration.ofMillis(HANDSHAKE_BUDGET_MILLIS))
            .callTimeout(Duration.ofMillis(HANDSHAKE_BUDGET_MILLIS))
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    OkHttpWebSocketTransport transport =
        new OkHttpWebSocketTransport(
            new OkHttpWebSocketTransport.OkHttpWebSocketDialer(client),
            URI.create("ws://dns-stalled.invalid/daemon"),
            1024);
    RecordingListener listener = new RecordingListener();
    try {
      CompletableFuture<DaemonConnection> stage = transport.connect(listener).toCompletableFuture();
      assertTrue(dnsEntered.await(THREAD_JOIN_MILLIS, TimeUnit.MILLISECONDS), "同步 DNS 应已阻塞");

      assertThrows(
          ExecutionException.class,
          () -> stage.get(HANDSHAKE_BUDGET_MILLIS + THREAD_JOIN_MILLIS, TimeUnit.MILLISECONDS));
    } finally {
      releaseDns.countDown();
      transport.close();
    }
    assertTrue(
        client
            .dispatcher()
            .executorService()
            .awaitTermination(THREAD_JOIN_MILLIS, TimeUnit.MILLISECONDS),
        "close 后自有 OkHttp 线程池必须终止");
  }

  /** close 之后拒绝新连接，且不再发起 upgrade。 */
  @Test
  void rejectsConnectAfterClose() {
    FakeDialer dialer = new FakeDialer();
    OkHttpWebSocketTransport transport = transport(dialer);
    transport.close();
    int dialsBefore = dialer.dialCalls.get();

    CompletionStage<DaemonConnection> stage = transport.connect(new RecordingListener());

    assertTrue(stage.toCompletableFuture().isCompletedExceptionally());
    assertEquals(dialsBefore, dialer.dialCalls.get());
  }

  /** 握手期限到期后迟到的 onOpen 不得复活连接：有界失败、原生句柄取消、恰好一次断开。 */
  @Test
  void ignoresLateOnOpenAfterHandshakeDeadline() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    CompletableFuture<DaemonConnection> stage = transport.connect(listener).toCompletableFuture();

    assertThrows(
        ExecutionException.class,
        () -> stage.get(HANDSHAKE_BUDGET_MILLIS + 3000, TimeUnit.MILLISECONDS));

    // 迟到 onOpen：不交付、不复活已取消连接、不投递后续帧。
    dialer.listener.onOpen(dialer.webSocket, response(DEFLATE));
    dialer.listener.onMessage(dialer.webSocket, "late");

    assertTrue(stage.isCompletedExceptionally());
    assertEquals(1, listener.disconnections.get());
    assertEquals(1, dialer.webSocket.cancelCalls.get());
    assertTrue(listener.messages.isEmpty());
    transport.close();
  }

  /** 超时与 close 竞争时只终结一次：一次失败结算、一次断开通知、一次取消。 */
  @Test
  void settlesHandshakeOnceWhenCloseRacesWithDialbacks() {
    FakeDialer dialer = new FakeDialer();
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    CompletableFuture<DaemonConnection> stage = transport.connect(listener).toCompletableFuture();

    // close 先到：pending 握手立即失败；随后迟到的 open/failure 不得改变终态。
    transport.close();
    dialer.listener.onOpen(dialer.webSocket, response(DEFLATE));
    dialer.listener.onFailure(dialer.webSocket, new IllegalStateException("late"), null);

    assertTrue(stage.isCompletedExceptionally());
    assertEquals(1, listener.disconnections.get());
    assertEquals(1, dialer.webSocket.cancelCalls.get());
  }

  /** transport close 收敛已交付连接：强制中止原生句柄并恰好通知一次断开。 */
  @Test
  void abortsDeliveredConnectionAndNotifiesOnceOnTransportClose() {
    FakeDialer dialer = new FakeDialer();
    dialer.synchronousOpen = true;
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    DaemonConnection connection = transport.connect(listener).toCompletableFuture().join();

    transport.close();

    assertFalse(connection.isOpen());
    assertEquals(1, dialer.webSocket.cancelCalls.get());
    assertEquals(1, listener.disconnections.get());
  }

  /** transport close 不因已请求 graceful close 而跳过取消：先发一次 close 帧，再强制取消原生句柄。 */
  @Test
  void cancelsNativeHandleAfterGracefulCloseRequestOnTransportClose() {
    FakeDialer dialer = new FakeDialer();
    dialer.synchronousOpen = true;
    RecordingListener listener = new RecordingListener();
    OkHttpWebSocketTransport transport = transport(dialer);
    DaemonConnection connection = transport.connect(listener).toCompletableFuture().join();

    connection.close();
    transport.close();

    assertEquals(1, dialer.webSocket.closeCalls.get());
    assertEquals(OkHttpWebSocketTransport.NORMAL_CLOSURE, dialer.webSocket.closeStatus);
    assertEquals(1, dialer.webSocket.cancelCalls.get());
    assertEquals(1, listener.disconnections.get());
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
    boundedTransport.close();
  }

  /** 完成一次成功握手并返回交付的连接，逼近生产时序：先 dial，再收到 101 + 扩展协商。 */
  private static DaemonConnection open(FakeDialer dialer, RecordingListener listener) {
    return open(dialer, listener, OkHttpWebSocketTransport.DEFAULT_MAX_INBOUND_TEXT_CHARS);
  }

  private static DaemonConnection open(
      FakeDialer dialer, RecordingListener listener, int maxInboundTextChars) {
    CompletionStage<DaemonConnection> stage =
        transport(dialer, maxInboundTextChars).connect(listener);
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

  /** 同步阻塞的 DNS 实现：制造 callTimeout 无法强制中断的解析阶段。 */
  private static final class BlockingDns implements Dns {

    private final CountDownLatch entered;
    private final CountDownLatch release;

    private BlockingDns(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
      throw new UnknownHostException(hostname);
    }
  }

  /** 捕获 upgrade 请求与 listener，让测试完全掌控握手与入站帧时序；dial 返回真实原生句柄。 */
  private static final class FakeDialer implements OkHttpWebSocketTransport.WebSocketDialer {

    private final List<Request> requests = new ArrayList<>();
    private final List<FakeWebSocket> sockets = new ArrayList<>();
    private final AtomicInteger dialCalls = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch dialEntered = new CountDownLatch(1);
    private volatile CountDownLatch dialGate;
    private WebSocketListener listener;
    private FakeWebSocket webSocket;
    private boolean synchronousOpen;
    private RuntimeException cancelFailure;
    private Throwable closeFailure;

    @Override
    public WebSocket dial(Request request, WebSocketListener listener) {
      dialCalls.incrementAndGet();
      requests.add(request);
      this.listener = listener;
      dialEntered.countDown();
      CountDownLatch gate = dialGate;
      if (gate != null) {
        try {
          gate.await();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
      // 原生句柄在闸门之后创建，使 close 与 dial 返回之间的竞争由测试确定性控制。
      this.webSocket = new FakeWebSocket(cancelFailure);
      sockets.add(webSocket);
      if (synchronousOpen) {
        listener.onOpen(webSocket, response(DEFLATE));
      }
      return webSocket;
    }

    @Override
    public void close() {
      closed.set(true);
      if (closeFailure != null) {
        rethrow(closeFailure);
      }
    }

    /** 让后续 dial 阻塞在给定的闸门上，由测试显式放行。 */
    void blockDial(CountDownLatch gate) {
      this.dialGate = gate;
    }

    boolean awaitDialEntered(long timeoutMillis) throws InterruptedException {
      return dialEntered.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    void releaseDial() {
      CountDownLatch gate = dialGate;
      if (gate != null) {
        gate.countDown();
      }
    }

    private static void rethrow(Throwable failure) {
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      throw (RuntimeException) failure;
    }
  }

  private static final class FailingListener implements DaemonTransportListener {

    private final RuntimeException failure;

    private FailingListener(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public void onMessage(String message) {
      // 本测试只关注断开回调失败时的收敛。
    }

    @Override
    public void onDisconnected(Throwable cause) {
      throw failure;
    }
  }

  private static final class FailingErrorListener implements DaemonTransportListener {

    private final Error failure;

    private FailingErrorListener(Error failure) {
      this.failure = failure;
    }

    @Override
    public void onMessage(String message) {
      // 本测试只关注断开回调抛出 Error 时的收敛。
    }

    @Override
    public void onDisconnected(Throwable cause) {
      throw failure;
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
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final RuntimeException cancelFailure;
    private volatile int closeStatus;

    private FakeWebSocket() {
      this(null);
    }

    private FakeWebSocket(RuntimeException cancelFailure) {
      this.cancelFailure = cancelFailure;
    }

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
    public void cancel() {
      cancelCalls.incrementAndGet();
      if (cancelFailure != null) {
        throw cancelFailure;
      }
    }
  }
}
