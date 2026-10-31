package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

class TcpTunnelStreamTest {

  @Test
  void readWriteAndHalfClose() throws Exception {
    try (AsynchronousServerSocketChannel server =
        AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();

      try (AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
        client.connect(address).get();
        try (AsynchronousSocketChannel accepted = server.accept().get()) {
          byte[] initial = "initial-bytes\n".getBytes(StandardCharsets.UTF_8);
          TcpTunnelStream stream = new TcpTunnelStream(client, initial);

          // 1. Initial prefetch bytes read first
          ByteBuffer chunk1 = stream.read().toCompletableFuture().get();
          assertNotNull(chunk1);
          byte[] bytes1 = new byte[chunk1.remaining()];
          chunk1.get(bytes1);
          assertArrayEquals(initial, bytes1);

          // 2. Client writes to accepted
          stream
              .write(ByteBuffer.wrap("hello-server".getBytes(StandardCharsets.UTF_8)))
              .toCompletableFuture()
              .get();
          ByteBuffer serverBuf = ByteBuffer.allocate(64);
          int n = accepted.read(serverBuf).get();
          assertEquals("hello-server", new String(serverBuf.array(), 0, n, StandardCharsets.UTF_8));

          // 3. Accepted writes to client
          ByteBuffer reply = ByteBuffer.wrap("reply-from-server".getBytes(StandardCharsets.UTF_8));
          accepted.write(reply).get();
          ByteBuffer chunk2 = stream.read().toCompletableFuture().get();
          assertNotNull(chunk2);
          byte[] bytes2 = new byte[chunk2.remaining()];
          chunk2.get(bytes2);
          assertEquals("reply-from-server", new String(bytes2, StandardCharsets.UTF_8));

          // 4. Half close output
          stream.endOutput().toCompletableFuture().get();
          ByteBuffer eofBuf = ByteBuffer.allocate(16);
          int eof = accepted.read(eofBuf).get();
          assertEquals(-1, eof);

          // 5. Server closes and client sees EOF
          accepted.close();
          ByteBuffer clientEof = stream.read().toCompletableFuture().get();
          assertNull(clientEof);

          stream.close();
        }
      }
    }
  }

  @Test
  void closeCancelsPendingOperations() throws Exception {
    try (AsynchronousServerSocketChannel server =
        AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();

      try (AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
        client.connect(address).get();
        try (AsynchronousSocketChannel accepted = server.accept().get()) {
          TcpTunnelStream stream = new TcpTunnelStream(client, null);

          // Start a pending read
          CompletableFuture<ByteBuffer> pendingRead = stream.read().toCompletableFuture();

          // Close stream
          stream.close();

          assertThrows(CancellationException.class, pendingRead::join);

          // Subsequent read fails with CancellationException
          assertThrows(
              CancellationException.class, () -> stream.read().toCompletableFuture().join());

          // Subsequent write fails with CancellationException
          assertThrows(
              CancellationException.class,
              () ->
                  stream
                      .write(ByteBuffer.wrap("fail".getBytes(StandardCharsets.UTF_8)))
                      .toCompletableFuture()
                      .join());
        }
      }
    }
  }

  @Test
  void validationChecks() throws IOException {
    try (AsynchronousSocketChannel channel = AsynchronousSocketChannel.open()) {
      TcpTunnelStream stream = new TcpTunnelStream(channel, null);
      assertThrows(IllegalArgumentException.class, () -> stream.write(null));
      assertThrows(IllegalArgumentException.class, () -> stream.write(ByteBuffer.allocate(0)));
      stream.close();
      stream.close(); // idempotent close
    }
  }

  @Test
  void endOutputPropagatesPriorWriteFailure() throws Exception {
    AsynchronousSocketChannel mockChannel = mock(AsynchronousSocketChannel.class);
    TcpTunnelStream stream = new TcpTunnelStream(mockChannel, null);
    doAnswer(
            inv -> {
              CompletionHandler<Integer, Void> handler = inv.getArgument(2);
              handler.failed(new IOException("write failed earlier"), null);
              return null;
            })
        .when(mockChannel)
        .write(any(ByteBuffer.class), any(), any());

    CompletableFuture<Void> writeFuture =
        stream.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    assertTrue(writeFuture.isCompletedExceptionally());

    // Calling endOutput after prior write failure must complete exceptionally
    CompletableFuture<Void> endFuture = stream.endOutput().toCompletableFuture();
    assertTrue(endFuture.isCompletedExceptionally());
  }

  @Test
  void concurrencyAndEndOutputScenarios() throws Exception {
    try (AsynchronousServerSocketChannel server =
        AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();

      try (AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
        client.connect(address).get();
        try (AsynchronousSocketChannel accepted = server.accept().get()) {
          TcpTunnelStream stream = new TcpTunnelStream(client, null);

          // 1. Concurrent read throws IllegalStateException
          CompletableFuture<ByteBuffer> r1 = stream.read().toCompletableFuture();
          assertThrows(IllegalStateException.class, stream::read);

          // Complete read by sending data from accepted
          accepted.write(ByteBuffer.wrap(new byte[] {1})).get();
          assertNotNull(r1.join());

          // 2. End output is idempotent
          CompletableFuture<Void> end1 = stream.endOutput().toCompletableFuture();
          end1.join();
          CompletableFuture<Void> end2 = stream.endOutput().toCompletableFuture();
          end2.join();

          // 3. Write after end output throws IllegalStateException
          assertThrows(
              IllegalStateException.class, () -> stream.write(ByteBuffer.wrap(new byte[] {2})));

          stream.close();
        }
      }
    }
  }

  @Test
  void asynchronousWriteCompletionAndFailureHandling() throws Exception {
    // 1. Write failure callback in CompletionHandler
    AsynchronousSocketChannel mockChannel = mock(AsynchronousSocketChannel.class);
    TcpTunnelStream stream = new TcpTunnelStream(mockChannel, null);
    doAnswer(
            inv -> {
              CompletionHandler<Integer, Void> handler = inv.getArgument(2);
              handler.failed(new IOException("simulated write failure"), null);
              return null;
            })
        .when(mockChannel)
        .write(any(ByteBuffer.class), any(), any());

    CompletableFuture<Void> writeFuture =
        stream.write(ByteBuffer.wrap(new byte[] {1, 2})).toCompletableFuture();
    assertTrue(writeFuture.isCompletedExceptionally());

    // 2. Multi-chunk write (source.hasRemaining() branch)
    AsynchronousSocketChannel mockChannel2 = mock(AsynchronousSocketChannel.class);
    TcpTunnelStream stream2 = new TcpTunnelStream(mockChannel2, null);
    ByteBuffer data = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
    doAnswer(
            inv -> {
              ByteBuffer buf = inv.getArgument(0);
              buf.position(buf.position() + 2);
              CompletionHandler<Integer, Void> handler = inv.getArgument(2);
              handler.completed(2, null);
              return null;
            })
        .doAnswer(
            inv -> {
              ByteBuffer buf = inv.getArgument(0);
              buf.position(buf.position() + 2);
              CompletionHandler<Integer, Void> handler = inv.getArgument(2);
              handler.completed(2, null);
              return null;
            })
        .when(mockChannel2)
        .write(any(ByteBuffer.class), any(), any());

    CompletableFuture<Void> writeFuture2 = stream2.write(data).toCompletableFuture();
    writeFuture2.join();
    assertTrue(writeFuture2.isDone() && !writeFuture2.isCompletedExceptionally());

    // 3. endOutput while write is pending
    AsynchronousSocketChannel mockChannel3 = mock(AsynchronousSocketChannel.class);
    TcpTunnelStream stream3 = new TcpTunnelStream(mockChannel3, null);
    AtomicReference<CompletionHandler<Integer, Void>> writeHolder = new AtomicReference<>();
    doAnswer(
            inv -> {
              writeHolder.set(inv.getArgument(2));
              ByteBuffer buf = inv.getArgument(0);
              buf.position(buf.limit());
              return null;
            })
        .when(mockChannel3)
        .write(any(ByteBuffer.class), any(), any());

    CompletableFuture<Void> wFuture =
        stream3.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    CompletableFuture<Void> endFuture = stream3.endOutput().toCompletableFuture();
    assertFalse(endFuture.isDone());

    writeHolder.get().completed(1, null);
    wFuture.join();
    endFuture.join();
    assertTrue(endFuture.isDone());
    verify(mockChannel3).shutdownOutput();

    // 4. Operations after close
    stream3.close();
    CompletableFuture<Void> endAfterClose = stream3.endOutput().toCompletableFuture();
    assertTrue(endAfterClose.isDone());
    CompletableFuture<Void> writeAfterClose =
        stream3.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    assertTrue(writeAfterClose.isCompletedExceptionally());

    // 5. Concurrent write not permitted
    AsynchronousSocketChannel mockChannel4 = mock(AsynchronousSocketChannel.class);
    TcpTunnelStream stream4 = new TcpTunnelStream(mockChannel4, null);
    stream4.write(ByteBuffer.wrap(new byte[] {1}));
    assertThrows(IllegalStateException.class, () -> stream4.write(ByteBuffer.wrap(new byte[] {2})));

    // 6. Close while write and endOutput are pending cancels them
    CompletableFuture<Void> end4 = stream4.endOutput().toCompletableFuture();
    stream4.close();
    assertTrue(end4.isCompletedExceptionally());

    // 7. endOutput when pending write fails
    AsynchronousSocketChannel mockChannel5 = mock(AsynchronousSocketChannel.class);
    TcpTunnelStream stream5 = new TcpTunnelStream(mockChannel5, null);
    AtomicReference<CompletionHandler<Integer, Void>> writeHolder5 = new AtomicReference<>();
    doAnswer(
            inv -> {
              writeHolder5.set(inv.getArgument(2));
              return null;
            })
        .when(mockChannel5)
        .write(any(ByteBuffer.class), any(), any());

    CompletableFuture<Void> w5 =
        stream5.write(ByteBuffer.wrap(new byte[] {1})).toCompletableFuture();
    CompletableFuture<Void> end5 = stream5.endOutput().toCompletableFuture();
    writeHolder5.get().failed(new IOException("write failed"), null);
    assertTrue(w5.isCompletedExceptionally());
    assertTrue(end5.isCompletedExceptionally());

    // 8. performShutdownOutput throws exception
    AsynchronousSocketChannel mockChannel6 = mock(AsynchronousSocketChannel.class);
    TcpTunnelStream stream6 = new TcpTunnelStream(mockChannel6, null);
    doThrow(new IOException("shutdown failure")).when(mockChannel6).shutdownOutput();
    CompletableFuture<Void> end6 = stream6.endOutput().toCompletableFuture();
    assertTrue(end6.isCompletedExceptionally());
  }
}
