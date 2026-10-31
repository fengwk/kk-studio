package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

class TcpTunnelStreamTest {

  @Test
  void readWriteAndHalfClose() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      int port = server.getLocalPort();
      try (Socket client = new Socket("127.0.0.1", port);
          Socket accepted = server.accept()) {

        byte[] initial = "initial-bytes\n".getBytes(StandardCharsets.UTF_8);
        TcpTunnelStream stream =
            new TcpTunnelStream(client, Executors.newSingleThreadExecutor(), initial);

        // First read yields initial bytes
        ByteBuffer chunk1 = stream.read().toCompletableFuture().get();
        assertNotNull(chunk1);
        byte[] bytes1 = new byte[chunk1.remaining()];
        chunk1.get(bytes1);
        assertArrayEquals(initial, bytes1);

        // Write from client to accepted
        stream
            .write(ByteBuffer.wrap("hello-server".getBytes(StandardCharsets.UTF_8)))
            .toCompletableFuture()
            .get();
        byte[] serverBuf = new byte[12];
        int n = accepted.getInputStream().read(serverBuf);
        assertEquals("hello-server", new String(serverBuf, 0, n, StandardCharsets.UTF_8));

        // Write from accepted to client
        accepted.getOutputStream().write("reply-from-server".getBytes(StandardCharsets.UTF_8));
        accepted.getOutputStream().flush();
        ByteBuffer chunk2 = stream.read().toCompletableFuture().get();
        assertNotNull(chunk2);
        byte[] bytes2 = new byte[chunk2.remaining()];
        chunk2.get(bytes2);
        assertEquals("reply-from-server", new String(bytes2, StandardCharsets.UTF_8));

        // Half close output
        stream.endOutput().toCompletableFuture().get();
        int eof = accepted.getInputStream().read();
        assertEquals(-1, eof);

        // Server closes and client sees EOF
        accepted.close();
        ByteBuffer eofChunk = stream.read().toCompletableFuture().get();
        assertNull(eofChunk);

        stream.close();
      }
    }
  }

  @Test
  void writeValidation() throws IOException {
    try (Socket s = new Socket()) {
      TcpTunnelStream stream = new TcpTunnelStream(s, Executors.newSingleThreadExecutor(), null);
      assertThrows(IllegalArgumentException.class, () -> stream.write(ByteBuffer.allocate(0)));
      stream.close();
    }
  }
}
