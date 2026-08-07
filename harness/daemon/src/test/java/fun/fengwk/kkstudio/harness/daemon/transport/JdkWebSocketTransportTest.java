package fun.fengwk.kkstudio.harness.daemon.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK WebSocket 适配器必须保留完整文本成帧以及 close/disconnect 所有权。 */
class JdkWebSocketTransportTest {

  /** 连接适配器发送完整帧，拒绝已关闭 socket，并发送一次 graceful close。 */
  @Test
  void adaptsSendAndCloseLifecycle() {
    FakeWebSocket webSocket = new FakeWebSocket();
    JdkWebSocketTransport.ConnectionAdapter connection =
        new JdkWebSocketTransport.ConnectionAdapter(webSocket);

    connection.sendText("hello").toCompletableFuture().join();
    assertEquals(List.of("hello"), webSocket.textMessages);
    assertTrue(connection.isOpen());

    connection.close();
    connection.close();
    assertEquals(1, webSocket.closeCalls.get());
    assertFalse(connection.isOpen());
    assertTrue(connection.sendText("after-close").toCompletableFuture().isCompletedExceptionally());
  }

  /** Listener 拼接分片文本、请求下一帧，并投递拼接后的完整消息。 */
  @Test
  void joinsTextFramesAndRequestsSubsequentFrames() {
    FakeWebSocket webSocket = new FakeWebSocket();
    RecordingListener listener = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter adapter = adapter(listener);

    adapter.onOpen(webSocket);
    adapter.onText(webSocket, "hel", false);
    adapter.onText(webSocket, "lo", true);

    assertEquals(List.of("hello"), listener.messages);
    assertEquals(3, webSocket.requestCalls.get());
  }

  /** 跨 fragment 累积超过上限时必须在继续累积前确定性拒绝：一次 close、无消息投递、不再消费。 */
  @Test
  void rejectsFragmentedTextMessageOverflowBeforeUnboundedAccumulation() {
    FakeWebSocket webSocket = new FakeWebSocket();
    RecordingListener listener = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter bounded = adapter(listener, 5);

    bounded.onOpen(webSocket);
    bounded.onText(webSocket, "abc", false);
    bounded.onText(webSocket, "de", false);
    assertEquals(0, webSocket.closeCalls.get());
    assertTrue(listener.messages.isEmpty());

    // 累积到 6 个字符，超过上限 5：拒绝且不投递。
    bounded.onText(webSocket, "f", true);
    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(JdkWebSocketTransport.POLICY_VIOLATION, webSocket.closeStatus);
    assertTrue(listener.messages.isEmpty());
    assertEquals(1, listener.disconnections.get());

    // 拒绝后继续到达的 fragment 不再消费或投递。
    int requests = webSocket.requestCalls.get();
    bounded.onText(webSocket, "g", true);
    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(requests, webSocket.requestCalls.get());
    assertTrue(listener.messages.isEmpty());
  }

  /** 单 fragment 超过上限同样拒绝；恰好等于上限的完整消息正常投递。 */
  @Test
  void rejectsSingleOversizedTextAndAcceptsMessagesAtTheLimit() {
    FakeWebSocket webSocket = new FakeWebSocket();
    RecordingListener oversized = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter adapter = adapter(oversized, 5);

    adapter.onOpen(webSocket);
    adapter.onText(webSocket, "abcdef", true);
    assertEquals(1, webSocket.closeCalls.get());
    assertTrue(oversized.messages.isEmpty());

    FakeWebSocket exactSocket = new FakeWebSocket();
    RecordingListener exact = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter exactAdapter = adapter(exact, 5);
    exactAdapter.onOpen(exactSocket);
    exactAdapter.onText(exactSocket, "abcde", true);
    assertEquals(List.of("abcde"), exact.messages);
    assertEquals(0, exactSocket.closeCalls.get());
  }

  /** binary 帧必须确定性拒绝，即使之前已经累积了部分文本。 */
  @Test
  void rejectsBinaryFramesAndClosesOnce() {
    FakeWebSocket webSocket = new FakeWebSocket();
    RecordingListener listener = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter adapter = adapter(listener);

    adapter.onOpen(webSocket);
    adapter.onText(webSocket, "hel", false);
    adapter.onBinary(webSocket, ByteBuffer.wrap(new byte[] {1}), false);

    assertEquals(1, webSocket.closeCalls.get());
    assertEquals(JdkWebSocketTransport.POLICY_VIOLATION, webSocket.closeStatus);
    assertTrue(listener.messages.isEmpty());
    assertEquals(1, listener.disconnections.get());

    adapter.onBinary(webSocket, ByteBuffer.wrap(new byte[] {2}), true);
    assertEquals(1, webSocket.closeCalls.get());
  }

  /** close 和 error 是竞争的终态通知，对 runtime 只能通知一次。 */
  @Test
  void notifiesDisconnectOnlyOnce() {
    FakeWebSocket webSocket = new FakeWebSocket();
    RecordingListener listener = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter adapter = adapter(listener);
    IllegalStateException error = new IllegalStateException("network");

    adapter.onError(webSocket, error);
    adapter.onClose(webSocket, WebSocket.NORMAL_CLOSURE, "closed");

    assertEquals(1, listener.disconnections.get());
    assertSame(error, listener.cause);
  }

  /** 非法上限与非法网关 URI 在构造期拒绝。 */
  @Test
  void rejectsInvalidConstructorArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JdkWebSocketTransport(URI.create("ws://localhost"), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JdkWebSocketTransport.ListenerAdapter(new RecordingListener(), -1));
  }

  /** 公共构造器与真实 JDK connect 装配：对不可达地址的握手必须异步失败而不是同步抛出。 */
  @Test
  void publicConstructorsAndRealConnectWiring() {
    URI unreachable = URI.create("ws://127.0.0.1:1/");
    JdkWebSocketTransport defaultTransport = new JdkWebSocketTransport(unreachable);
    JdkWebSocketTransport boundedTransport = new JdkWebSocketTransport(unreachable, 1024);

    RecordingListener listener = new RecordingListener();
    CompletionStage<DaemonConnection> stage = boundedTransport.connect(listener);
    assertNotNull(stage);
    assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertNotNull(defaultTransport.connect(listener));
  }

  private static JdkWebSocketTransport.ListenerAdapter adapter(RecordingListener listener) {
    return adapter(listener, JdkWebSocketTransport.DEFAULT_MAX_INBOUND_TEXT_CHARS);
  }

  private static JdkWebSocketTransport.ListenerAdapter adapter(
      RecordingListener listener, int maxInboundTextChars) {
    return new JdkWebSocketTransport.ListenerAdapter(listener, maxInboundTextChars);
  }

  private static final class RecordingListener implements DaemonTransportListener {

    private final List<String> messages = new ArrayList<>();
    private final AtomicInteger disconnections = new AtomicInteger();
    private Throwable cause;

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
    private final AtomicInteger requestCalls = new AtomicInteger();
    private volatile int closeStatus;
    private boolean outputClosed;

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence message, boolean last) {
      textMessages.add(message.toString());
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer message, boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      closeCalls.incrementAndGet();
      closeStatus = statusCode;
      outputClosed = true;
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {
      requestCalls.incrementAndGet();
    }

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return outputClosed;
    }

    @Override
    public boolean isInputClosed() {
      return false;
    }

    @Override
    public void abort() {}
  }
}
