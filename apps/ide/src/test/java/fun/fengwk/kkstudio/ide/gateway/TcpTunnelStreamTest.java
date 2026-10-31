package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

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
      assertThrows(IllegalArgumentException.class, () -> stream.write(ByteBuffer.allocate(0)));
      stream.close();
    }
  }
}
