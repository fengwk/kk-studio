package fun.fengwk.kkstudio.harness.daemon.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK WebSocket adapter must retain complete-text framing and close/disconnect ownership. */
class JdkWebSocketTransportTest {

  /**
   * Connection adapter sends complete frames, rejects closed sockets, and sends one graceful close.
   */
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

  /** Listener joins fragmented text, requests the next frame, and ignores binary payloads. */
  @Test
  void joinsTextFramesAndRequestsSubsequentFrames() {
    FakeWebSocket webSocket = new FakeWebSocket();
    RecordingListener listener = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter adapter =
        new JdkWebSocketTransport.ListenerAdapter(listener);

    adapter.onOpen(webSocket);
    adapter.onText(webSocket, "hel", false);
    adapter.onBinary(webSocket, ByteBuffer.wrap(new byte[] {1}), true);
    adapter.onText(webSocket, "lo", true);

    assertEquals(List.of("hello"), listener.messages);
    assertEquals(4, webSocket.requestCalls.get());
  }

  /** Close and error are competing terminal notifications and must only notify the runtime once. */
  @Test
  void notifiesDisconnectOnlyOnce() {
    FakeWebSocket webSocket = new FakeWebSocket();
    RecordingListener listener = new RecordingListener();
    JdkWebSocketTransport.ListenerAdapter adapter =
        new JdkWebSocketTransport.ListenerAdapter(listener);
    IllegalStateException error = new IllegalStateException("network");

    adapter.onError(webSocket, error);
    adapter.onClose(webSocket, WebSocket.NORMAL_CLOSURE, "closed");

    assertEquals(1, listener.disconnections.get());
    assertSame(error, listener.cause);
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
