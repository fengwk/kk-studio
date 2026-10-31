package fun.fengwk.kkstudio.harness.environment.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Callback;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
  void demandDrivenInboundBackpressure() throws Exception {
    // 1. 打开时调用一次 demand
    endPoint.onOpen();
    assertEquals(1, pipe.demandCount.get());

    // 2. 收到一条消息，未消费时不 demand 下一条
    AtomicBoolean msg1Done = new AtomicBoolean(false);
    endPoint.onInboundData(
        ByteBuffer.wrap(new byte[] {1, 2, 3}),
        Callback.from(() -> msg1Done.set(true), ignored -> {}));
    assertEquals(1, pipe.demandCount.get());

    // 3. 部分消费（2 字节），未完全消费，不 demand
    ByteBuffer partial = ByteBuffer.allocate(2);
    assertEquals(2, endPoint.fill(partial));
    assertFalse(msg1Done.get());
    assertEquals(1, pipe.demandCount.get());

    // 4. 完全消费剩余 1 字节，触发 callback 并 demand 下一条消息
    ByteBuffer remaining = ByteBuffer.allocate(2);
    assertEquals(1, endPoint.fill(remaining));
    assertTrue(msg1Done.get());
    assertEquals(2, pipe.demandCount.get());

    // 5. 收到第二条消息，完全消费后 demand 第三次
    AtomicBoolean msg2Done = new AtomicBoolean(false);
    endPoint.onInboundData(
        ByteBuffer.wrap(new byte[] {4, 5}), Callback.from(() -> msg2Done.set(true), ignored -> {}));
    assertEquals(2, pipe.demandCount.get());

    ByteBuffer second = ByteBuffer.allocate(5);
    assertEquals(2, endPoint.fill(second));
    assertTrue(msg2Done.get());
    assertEquals(3, pipe.demandCount.get());
  }

  @Test
  void exactlyOneDemandPerConsumedMessage() throws Exception {
    endPoint.onOpen();
    assertEquals(1, pipe.demandCount.get(), "打开时触发恰好一次 demand");

    // 第一条消息：分两次消费，消费完毕才触发第二次 demand
    AtomicBoolean msg1Done = new AtomicBoolean(false);
    endPoint.onInboundData(
        ByteBuffer.wrap(new byte[] {1, 2, 3, 4}),
        Callback.from(() -> msg1Done.set(true), ignored -> {}));
    assertEquals(1, pipe.demandCount.get(), "收到消息未完全消费时不得触发额外 demand");

    endPoint.fill(ByteBuffer.allocate(2));
    assertFalse(msg1Done.get());
    assertEquals(1, pipe.demandCount.get(), "部分消费不得触发 demand");

    endPoint.fill(ByteBuffer.allocate(2));
    assertTrue(msg1Done.get());
    assertEquals(2, pipe.demandCount.get(), "消息完全消费后恰好触发一次 demand");

    // 第二条消息：一次性完整消费
    AtomicBoolean msg2Done = new AtomicBoolean(false);
    endPoint.onInboundData(
        ByteBuffer.wrap(new byte[] {5, 6}), Callback.from(() -> msg2Done.set(true), ignored -> {}));
    assertEquals(2, pipe.demandCount.get());

    endPoint.fill(ByteBuffer.allocate(10));
    assertTrue(msg2Done.get());
    assertEquals(3, pipe.demandCount.get(), "整条消息消费完毕后恰好触发一次 demand");

    // 第三条消息：空 payload 消息，直接完成并触发一次 demand
    AtomicBoolean msg3Done = new AtomicBoolean(false);
    endPoint.onInboundData(
        ByteBuffer.allocate(0), Callback.from(() -> msg3Done.set(true), ignored -> {}));
    assertTrue(msg3Done.get());
    assertEquals(4, pipe.demandCount.get(), "空消息直接完成并恰好触发一次 demand");
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
  void fillAppendsToFlushModeBuffer() throws Exception {
    endPoint.onOpen();
    endPoint.onInboundData(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), Callback.NOOP);

    // BufferUtil.allocate 返回处于 Flush 模式（pos=0, limit=0, cap=10）的缓冲
    ByteBuffer flushBuffer = BufferUtil.allocate(10);
    assertEquals(0, flushBuffer.remaining());

    int filled = endPoint.fill(flushBuffer);
    assertEquals(4, filled);
    assertEquals(4, flushBuffer.remaining());
    assertEquals(1, flushBuffer.get());
    assertEquals(2, flushBuffer.get());
    assertEquals(3, flushBuffer.get());
    assertEquals(4, flushBuffer.get());
  }

  @Test
  void chunksLargeWriteAndDrivesWriteFlusher() throws Exception {
    int totalSize = 2 * 1024 * 1024 + 100; // 2 MiB + 100 bytes
    byte[] payload = new byte[totalSize];
    for (int i = 0; i < totalSize; i++) {
      payload[i] = (byte) (i & 0x7f);
    }

    ByteBuffer buffer = ByteBuffer.wrap(payload);
    // 第一次 flush 发出第 1 个 1 MiB 块，仍有剩余返回 false
    assertFalse(endPoint.flush(buffer));

    assertEquals(1, pipe.sentMessages.size());
    assertEquals(WebSocketEndPoint.MAX_MESSAGE_BYTES, pipe.sentMessages.get(0).remaining());

    // 模拟底层第 1 块发送成功，驱动 completeWrite
    pipe.completeLatestSend();

    // 第二次 flush 发出第 2 个 1 MiB 块
    assertFalse(endPoint.flush(buffer));
    assertEquals(2, pipe.sentMessages.size());
    assertEquals(WebSocketEndPoint.MAX_MESSAGE_BYTES, pipe.sentMessages.get(1).remaining());

    // 模拟底层第 2 块发送成功
    pipe.completeLatestSend();

    // 第三次 flush 发出最后剩余的 100 字节
    assertFalse(endPoint.flush(buffer));
    assertEquals(3, pipe.sentMessages.size());
    assertEquals(100, pipe.sentMessages.get(2).remaining());

    // 模拟底层最后一块发送成功
    pipe.completeLatestSend();
    // 缓冲区已空，返回 true
    assertTrue(endPoint.flush(buffer));
  }

  @Test
  void writeFailurePropagatesToFlusherAndClosesEndPoint() throws Exception {
    ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3});
    assertFalse(endPoint.flush(buffer));

    pipe.failLatestSend(new IOException("transport down"));
    assertThrows(IOException.class, () -> endPoint.fill(ByteBuffer.allocate(10)));
  }

  @Test
  void synchronousSendBinaryCompletesWriteWithoutHanging() throws Exception {
    pipe.synchronousSend = true;
    TestUtilCallback callback = new TestUtilCallback();
    ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3});

    endPoint.write(callback, buffer);

    assertDoesNotThrow(() -> callback.future().get(2, TimeUnit.SECONDS));
  }

  @Test
  void closedEndpointRejectsFurtherOperations() throws Exception {
    endPoint.close();
    assertTrue(pipe.closed);
    assertThrows(
        ClosedChannelException.class, () -> endPoint.flush(ByteBuffer.wrap(new byte[] {1})));
  }

  @Test
  void onInboundErrorClosesEndpointAndPropagatesToFillAndFlush() throws Exception {
    endPoint.onInboundError(new IOException("simulated network loss"));
    assertThrows(IOException.class, () -> endPoint.fill(ByteBuffer.allocate(10)));
    assertThrows(IOException.class, () -> endPoint.flush(ByteBuffer.wrap(new byte[] {1})));
  }

  @Test
  void inboundDataRejectedAfterClose() throws Exception {
    endPoint.close();
    AtomicBoolean completed = new AtomicBoolean(false);
    endPoint.onInboundData(
        ByteBuffer.wrap(new byte[] {1, 2}),
        Callback.from(() -> completed.set(true), ignored -> {}));
    assertTrue(completed.get());
    assertEquals(-1, endPoint.fill(ByteBuffer.allocate(10)));
  }

  @Test
  void addressAndPipeAccessors() {
    assertEquals(pipe, endPoint.getTransport());
    assertEquals(pipe.getLocalSocketAddress(), endPoint.getLocalSocketAddress());
    assertEquals(pipe.getRemoteSocketAddress(), endPoint.getRemoteSocketAddress());
  }

  @Test
  void shutdownOutputClosesPipe() {
    endPoint.shutdownOutput();
    assertTrue(pipe.closed);
  }

  private static final class TestPipe implements WebSocketPipe {
    private final List<ByteBuffer> sentMessages = new ArrayList<>();
    private final Queue<Callback> pendingCallbacks = new ArrayDeque<>();
    private final AtomicInteger demandCount = new AtomicInteger();
    private boolean closed;
    private boolean synchronousSend;

    @Override
    public void sendBinary(ByteBuffer payload, Callback callback) {
      byte[] copy = new byte[payload.remaining()];
      payload.get(copy);
      sentMessages.add(ByteBuffer.wrap(copy));
      if (synchronousSend) {
        callback.succeed();
      } else {
        pendingCallbacks.add(callback);
      }
    }

    @Override
    public void demand() {
      demandCount.incrementAndGet();
    }

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
        cb.succeed();
      }
    }

    void failLatestSend(Throwable cause) {
      Callback cb = pendingCallbacks.poll();
      if (cb != null) {
        cb.fail(cause);
      }
    }
  }
}
