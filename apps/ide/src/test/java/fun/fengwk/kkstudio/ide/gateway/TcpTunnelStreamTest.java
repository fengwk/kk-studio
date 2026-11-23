package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** 覆盖 {@link TcpTunnelStream} 的中继行为、半关闭顺序与取消语义。 */
class TcpTunnelStreamTest {

  private static final long TIMEOUT_MILLIS = 5000;

  /** 单次读取返回字节数的上限。 */
  private static final int MAX_READ_CHUNK = 16 * 1024;

  @Test
  void relayInitialBytesWriteReadAndHalfClose() throws Exception {
    try (SocketPair pair = openPair()) {
      byte[] initial = "initial-bytes\n".getBytes(StandardCharsets.UTF_8);
      TcpTunnelStream stream = new TcpTunnelStream(pair.client(), initial);

      // 预读的响应头溢出字节先于连接上的数据返回
      assertArrayEquals(initial, chunk(stream.read()));

      // 上行写入完整到达对端
      byte[] upstream = "hello-server".getBytes(StandardCharsets.UTF_8);
      stream
          .write(ByteBuffer.wrap(upstream))
          .toCompletableFuture()
          .get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      assertArrayEquals(upstream, readFully(pair.peer().getInputStream(), upstream.length));

      // 下行数据按块返回
      byte[] downstream = "reply-from-server".getBytes(StandardCharsets.UTF_8);
      OutputStream peerOutput = pair.peer().getOutputStream();
      peerOutput.write(downstream);
      peerOutput.flush();
      assertArrayEquals(downstream, chunk(stream.read()));

      // 半关闭幂等：重复调用返回同一次结果，对端读到 EOF，写方向拒绝新写入
      CompletionStage<Void> halfClose = stream.endOutput();
      halfClose.toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      assertSame(halfClose, stream.endOutput());
      assertEquals(-1, pair.peer().getInputStream().read());
      assertThrows(
          IllegalStateException.class, () -> stream.write(ByteBuffer.wrap(new byte[] {1})));

      // 对端关闭后读方向返回 EOF，且后续读取持续返回 EOF
      pair.peer().close();
      assertNull(await(stream.read()));
      assertNull(await(stream.read()));

      stream.close();
      stream.close();
    }
  }

  @Test
  void readsAreBoundedChunksAndPreserveOrder() throws Exception {
    try (SocketPair pair = openPair()) {
      TcpTunnelStream stream = new TcpTunnelStream(pair.client(), new byte[0]);
      byte[] payload = new byte[64 * 1024];
      new Random(7).nextBytes(payload);

      // 大于单次读取上限的写入被完整按序发送
      stream
          .write(ByteBuffer.wrap(payload.clone()))
          .toCompletableFuture()
          .get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      assertArrayEquals(payload, readFully(pair.peer().getInputStream(), payload.length));

      // 对端回写同样大小的数据，读取按有界块返回且顺序不变
      OutputStream peerOutput = pair.peer().getOutputStream();
      peerOutput.write(payload);
      peerOutput.flush();

      ByteArrayOutputStream received = new ByteArrayOutputStream();
      int chunks = 0;
      while (received.size() < payload.length) {
        ByteBuffer chunk = await(stream.read());
        assertNotNull(chunk, "expected a data chunk");
        assertTrue(chunk.remaining() <= MAX_READ_CHUNK, "chunk exceeds bound: " + chunk);
        chunks++;
        byte[] bytes = remainingBytes(chunk);
        received.write(bytes, 0, bytes.length);
      }
      assertTrue(chunks > 1, "expected bounded chunks instead of one large read");
      assertArrayEquals(payload, received.toByteArray());

      stream.close();
    }
  }

  @Test
  void closeCancelsPendingReadAndRejectsNewOperations() throws Exception {
    try (SocketPair pair = openPair()) {
      TcpTunnelStream stream = new TcpTunnelStream(pair.client(), null);
      CompletableFuture<ByteBuffer> pendingRead = stream.read().toCompletableFuture();

      stream.close();

      assertFailsWith(CancellationException.class, pendingRead);
      assertFailsWith(CancellationException.class, stream.read().toCompletableFuture());
      assertFailsWith(
          CancellationException.class,
          stream.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture());
      assertNull(stream.endOutput().toCompletableFuture().join());

      // 连接确实已释放，对端读到 EOF
      assertEquals(-1, pair.peer().getInputStream().read());
    }
  }

  @Test
  void onePendingOperationPerDirectionIsEnforced() throws Exception {
    Socket socket = mock(Socket.class);
    CountDownLatch readGate = new CountDownLatch(1);
    CountDownLatch writeGate = new CountDownLatch(1);
    byte[] inbound = "inbound".getBytes(StandardCharsets.UTF_8);
    OutputStream output = blockingOutput(writeGate);
    when(socket.getInputStream()).thenReturn(gatedInput(readGate, inbound));
    when(socket.getOutputStream()).thenReturn(output);
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    CompletableFuture<ByteBuffer> pendingRead = stream.read().toCompletableFuture();
    IllegalStateException readError = assertThrows(IllegalStateException.class, stream::read);
    assertEquals("concurrent read not permitted", readError.getMessage());

    CompletableFuture<Void> pendingWrite =
        stream.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    IllegalStateException writeError =
        assertThrows(
            IllegalStateException.class, () -> stream.write(ByteBuffer.wrap(new byte[] {2})));
    assertEquals("concurrent write not permitted", writeError.getMessage());

    readGate.countDown();
    assertArrayEquals(inbound, remainingBytes(await(pendingRead)));
    writeGate.countDown();
    pendingWrite.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    stream.close();
  }

  @Test
  void endOutputWaitsForPendingWriteThenHalfCloses() throws Exception {
    Socket socket = mock(Socket.class);
    CountDownLatch gate = new CountDownLatch(1);
    OutputStream output = blockingOutput(gate);
    when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(socket.getOutputStream()).thenReturn(output);
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    CompletableFuture<Void> writeFuture =
        stream.write(ByteBuffer.wrap(new byte[] {1, 2, 3})).toCompletableFuture();
    CompletableFuture<Void> endFuture = stream.endOutput().toCompletableFuture();
    assertFalse(endFuture.isDone());
    verify(socket, never()).shutdownOutput();

    gate.countDown();
    writeFuture.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    endFuture.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    InOrder inOrder = inOrder(output, socket);
    inOrder.verify(output).write(any(byte[].class), anyInt(), anyInt());
    inOrder.verify(socket).shutdownOutput();

    stream.close();
  }

  @Test
  void endOutputFailsWhenPendingWriteFails() throws Exception {
    Socket socket = mock(Socket.class);
    IOException failure = new IOException("write failed while pending");
    CountDownLatch gate = new CountDownLatch(1);
    OutputStream output = mock(OutputStream.class);
    doAnswer(
            invocation -> {
              gate.await();
              throw failure;
            })
        .when(output)
        .write(any(byte[].class), anyInt(), anyInt());
    when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(socket.getOutputStream()).thenReturn(output);
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    CompletableFuture<Void> writeFuture =
        stream.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    CompletableFuture<Void> endFuture = stream.endOutput().toCompletableFuture();
    gate.countDown();

    assertSame(failure, assertFailsWith(IOException.class, writeFuture));
    assertSame(failure, assertFailsWith(IOException.class, endFuture));
    verify(socket, never()).shutdownOutput();

    stream.close();
  }

  @Test
  void endOutputPropagatesEarlierWriteFailure() throws Exception {
    Socket socket = mock(Socket.class);
    IOException failure = new IOException("write failed earlier");
    when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(socket.getOutputStream()).thenReturn(failingOutput(failure));
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    CompletableFuture<Void> writeFuture =
        stream.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    assertSame(failure, assertFailsWith(IOException.class, writeFuture));
    assertSame(
        failure, assertFailsWith(IOException.class, stream.endOutput().toCompletableFuture()));
    verify(socket, never()).shutdownOutput();

    stream.close();
  }

  @Test
  void endOutputFailsWhenHalfCloseFails() throws Exception {
    Socket socket = mock(Socket.class);
    when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(socket.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
    doThrow(new IOException("shutdown failure")).when(socket).shutdownOutput();
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    CompletionStage<Void> halfClose = stream.endOutput();
    assertSame(halfClose, stream.endOutput());
    assertEquals(
        "shutdown failure",
        assertFailsWith(IOException.class, halfClose.toCompletableFuture()).getMessage());

    stream.close();
  }

  @Test
  void closeCancelsPendingWriteAndHalfClose() throws Exception {
    Socket socket = mock(Socket.class);
    CountDownLatch gate = new CountDownLatch(1);
    OutputStream output = blockingOutput(gate);
    when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(socket.getOutputStream()).thenReturn(output);
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    CompletableFuture<Void> writeFuture =
        stream.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    CompletableFuture<Void> endFuture = stream.endOutput().toCompletableFuture();
    assertFalse(endFuture.isDone());

    stream.close();

    assertFailsWith(CancellationException.class, writeFuture);
    assertFailsWith(CancellationException.class, endFuture);
    verify(socket).close();

    gate.countDown();
  }

  @Test
  void writeArgumentsAreValidatedAndCloseIsIdempotent() throws Exception {
    Socket socket = mock(Socket.class);
    when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(socket.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    assertThrows(IllegalArgumentException.class, () -> stream.write(null));
    assertThrows(IllegalArgumentException.class, () -> stream.write(ByteBuffer.allocate(0)));

    stream.close();
    stream.close();
    verify(socket, times(1)).close();
  }

  @Test
  void closeAbortsRawTransportBeforeLayeredSocket() throws Exception {
    Socket layered = mock(Socket.class);
    Socket transport = mock(Socket.class);
    CountDownLatch gate = new CountDownLatch(1);
    when(layered.getInputStream()).thenReturn(gatedInput(gate, new byte[] {1}));
    when(layered.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
    TcpTunnelStream stream = new TcpTunnelStream(layered, transport, null);

    CompletableFuture<ByteBuffer> pendingRead = stream.read().toCompletableFuture();
    stream.close();

    assertFailsWith(CancellationException.class, pendingRead);
    InOrder inOrder = inOrder(transport, layered);
    inOrder.verify(transport).close();
    inOrder.verify(layered).close();

    gate.countDown();
  }

  @Test
  void readFailureSurfacesThroughThePendingRead() throws Exception {
    Socket socket = mock(Socket.class);
    IOException failure = new IOException("read failure");
    when(socket.getInputStream()).thenReturn(failingInput(failure));
    when(socket.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    assertSame(failure, assertFailsWith(IOException.class, stream.read().toCompletableFuture()));

    stream.close();
  }

  @Test
  void constructorPropagatesStreamOpenFailure() throws IOException {
    Socket socket = mock(Socket.class);
    when(socket.getInputStream()).thenThrow(new IOException("input stream unavailable"));

    assertThrows(IOException.class, () -> new TcpTunnelStream(socket, null));
  }

  @Test
  void closeFailuresAreTolerated() throws Exception {
    Socket socket = mock(Socket.class);
    when(socket.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(socket.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
    doThrow(new IOException("close failure")).when(socket).close();
    TcpTunnelStream stream = new TcpTunnelStream(socket, null);

    stream.close();

    verify(socket).close();
  }

  private static ByteBuffer await(CompletionStage<ByteBuffer> stage) throws Exception {
    return stage.toCompletableFuture().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
  }

  private static byte[] chunk(CompletionStage<ByteBuffer> stage) throws Exception {
    ByteBuffer chunk = await(stage);
    assertNotNull(chunk, "expected a data chunk");
    return remainingBytes(chunk);
  }

  private static byte[] remainingBytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  private static byte[] readFully(InputStream input, int length) throws IOException {
    byte[] data = new byte[length];
    int read = 0;
    while (read < length) {
      int count = input.read(data, read, length - read);
      if (count < 0) {
        throw new IOException("expected " + length + " bytes but received " + read);
      }
      read += count;
    }
    return data;
  }

  /** 断言操作以给定类型的异常完成并返回该异常。 */
  private static Throwable assertFailsWith(
      Class<? extends Throwable> expected, CompletableFuture<?> future) throws Exception {
    if (expected == CancellationException.class) {
      return assertThrows(
          CancellationException.class, () -> future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    }
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    assertTrue(
        expected.isInstance(error.getCause()), "unexpected failure cause: " + error.getCause());
    return error.getCause();
  }

  /** 闸门释放前写入阻塞，用于观察写方向尚未完成时的状态。 */
  private static OutputStream blockingOutput(CountDownLatch gate) throws IOException {
    OutputStream output = mock(OutputStream.class);
    doAnswer(
            invocation -> {
              gate.await();
              return null;
            })
        .when(output)
        .write(any(byte[].class), anyInt(), anyInt());
    return output;
  }

  /** 闸门释放前读取阻塞，之后按给出的字节返回数据。 */
  private static InputStream gatedInput(CountDownLatch gate, byte[] data) {
    return new InputStream() {
      private int position;

      @Override
      public int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read < 0 ? -1 : single[0] & 0xFF;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
          gate.await();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while waiting for the test gate", error);
        }
        if (position >= data.length) {
          return -1;
        }
        int read = Math.min(length, data.length - position);
        System.arraycopy(data, position, buffer, offset, read);
        position += read;
        return read;
      }
    };
  }

  private static InputStream failingInput(IOException failure) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        throw failure;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        throw failure;
      }
    };
  }

  private static OutputStream failingOutput(IOException failure) {
    return new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        throw failure;
      }

      @Override
      public void write(byte[] buffer, int offset, int length) throws IOException {
        throw failure;
      }
    };
  }

  private static SocketPair openPair() throws IOException {
    ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    Socket client = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
    client.setSoTimeout((int) TIMEOUT_MILLIS);
    Socket peer = serverSocket.accept();
    peer.setSoTimeout((int) TIMEOUT_MILLIS);
    return new SocketPair(serverSocket, client, peer);
  }

  /** 一对已连接的 TCP socket：{@code client} 交给被测流，{@code peer} 充当对端。 */
  private record SocketPair(ServerSocket serverSocket, Socket client, Socket peer)
      implements AutoCloseable {

    @Override
    public void close() throws IOException {
      serverSocket.close();
      client.close();
      peer.close();
    }
  }
}
