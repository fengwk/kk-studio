package fun.fengwk.kkstudio.harness.environment.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

class WebSocketEndPointTest {

  private Scheduler scheduler;
  private TestPipe pipe;
  private WebSocketEndPoint endPoint;

  @BeforeEach
  void setUp() throws Exception {
    scheduler = new ScheduledExecutorScheduler();
    scheduler.start();
    pipe = new TestPipe();
    endPoint = new WebSocketEndPoint(scheduler, pipe);
  }

  @AfterEach
  void tearDown() throws Exception {
    endPoint.close();
    scheduler.stop();
  }

  @Test
  void readsInboundDataInOrderAndFreesCallback() throws Exception {
    AtomicBoolean callback1Done = new AtomicBoolean(false);
    AtomicBoolean callback2Done = new AtomicBoolean(false);

    endPoint.onInboundData(
        ByteBuffer.wrap(new byte[] {1, 2, 3}),
        Callback.from(() -> callback1Done.set(true), ignored -> {}));
    endPoint.onInboundData(
        ByteBuffer.wrap(new byte[] {4, 5}),
        Callback.from(() -> callback2Done.set(true), ignored -> {}));

    ByteBuffer target = ByteBuffer.allocate(10);
    int readCount = endPoint.fill(target);
    assertEquals(5, readCount);
    target.flip();
    byte[] readBytes = new byte[target.remaining()];
    target.get(readBytes);
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, readBytes);

    assertTrue(callback1Done.get());
    assertTrue(callback2Done.get());
  }

  @Test
  void fillReturnsZeroWhenEmptyAndMinusOneOnEof() throws Exception {
    ByteBuffer target = ByteBuffer.allocate(10);
    assertEquals(0, endPoint.fill(target));

    endPoint.onInboundEof();
    assertEquals(-1, endPoint.fill(target));
    assertTrue(endPoint.isInputShutdown());
  }

  @Test
  void chunksLargeWriteAndDrivesWriteFlusher() throws Exception {
    int totalSize = 2 * 1024 * 1024 + 100; // 2 MiB + 100 bytes
    byte[] payload = new byte[totalSize];
    for (int i = 0; i < totalSize; i++) {
      payload[i] = (byte) (i & 0x7f);
    }

    AtomicBoolean writeCompleted = new AtomicBoolean(false);
    endPoint.write(
        Callback.from(() -> writeCompleted.set(true), ignored -> {}), ByteBuffer.wrap(payload));

    // 第一次发送应该发出第 1 个 1 MiB 块
    assertEquals(1, pipe.sentMessages.size());
    assertEquals(WebSocketEndPoint.MAX_MESSAGE_BYTES, pipe.sentMessages.get(0).remaining());
    assertFalse(writeCompleted.get());

    // 模拟底层第 1 块发送成功，驱动 completeWrite
    pipe.completeLatestSend();

    // 应该发出第 2 个 1 MiB 块
    assertEquals(2, pipe.sentMessages.size());
    assertEquals(WebSocketEndPoint.MAX_MESSAGE_BYTES, pipe.sentMessages.get(1).remaining());
    assertFalse(writeCompleted.get());

    // 模拟底层第 2 块发送成功
    pipe.completeLatestSend();

    // 应该发出最后剩余的 100 字节
    assertEquals(3, pipe.sentMessages.size());
    assertEquals(100, pipe.sentMessages.get(2).remaining());
    assertFalse(writeCompleted.get());

    // 模拟底层最后一块发送成功
    pipe.completeLatestSend();
    assertTrue(writeCompleted.get());
  }

  @Test
  void writeFailurePropagatesToFlusherAndClosesEndPoint() {
    AtomicBoolean failed = new AtomicBoolean(false);
    endPoint.write(
        Callback.from(() -> {}, err -> failed.set(true)), ByteBuffer.wrap(new byte[] {1, 2, 3}));

    pipe.failLatestSend(new IOException("transport down"));
    assertTrue(failed.get());
    assertThrows(IOException.class, () -> endPoint.fill(ByteBuffer.allocate(10)));
  }

  @Test
  void closedEndpointRejectsFurtherOperations() throws Exception {
    endPoint.close();
    assertTrue(pipe.closed);
    assertThrows(
        ClosedChannelException.class, () -> endPoint.flush(ByteBuffer.wrap(new byte[] {1})));
  }

  private static final class TestPipe implements WebSocketPipe {
    private final List<ByteBuffer> sentMessages = new ArrayList<>();
    private final Queue<Callback> pendingCallbacks = new ArrayDeque<>();
    private boolean closed;

    @Override
    public void sendBinary(ByteBuffer payload, Callback callback) {
      byte[] copy = new byte[payload.remaining()];
      payload.get(copy);
      sentMessages.add(ByteBuffer.wrap(copy));
      pendingCallbacks.add(callback);
    }

    @Override
    public void demand() {}

    @Override
    public void close(int statusCode, String reason) {
      this.closed = true;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
      return new InetSocketAddress("127.0.0.1", 1234);
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
      return new InetSocketAddress("127.0.0.1", 5678);
    }

    void completeLatestSend() {
      Callback cb = pendingCallbacks.poll();
      if (cb != null) {
        cb.succeeded();
      }
    }

    void failLatestSend(Throwable cause) {
      Callback cb = pendingCallbacks.poll();
      if (cb != null) {
        cb.failed(cause);
      }
    }
  }
}
