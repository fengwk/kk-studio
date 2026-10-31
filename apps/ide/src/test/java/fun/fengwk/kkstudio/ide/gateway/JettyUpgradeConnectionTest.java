package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class JettyUpgradeConnectionTest {

  @Test
  void prefetchedBytesForwardedOnOpen() throws Exception {
    EndPoint endPoint = mock(EndPoint.class);
    TestTunnelStream tunnel = new TestTunnelStream();

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(endPoint, Executors.newSingleThreadExecutor(), tunnel);

    ByteBuffer prefetched = ByteBuffer.wrap("prefetched".getBytes(StandardCharsets.UTF_8));
    connection.onUpgradeTo(prefetched);
    connection.onOpen();

    ByteBuffer received = tunnel.pollWrittenChunk();
    byte[] bytes = new byte[received.remaining()];
    received.get(bytes);
    assertEquals("prefetched", new String(bytes, StandardCharsets.UTF_8));

    connection.close();
  }

  @Test
  void onFillablePumpsToTunnel() throws Exception {
    EndPoint endPoint = mock(EndPoint.class);
    TestTunnelStream tunnel = new TestTunnelStream();

    when(endPoint.fill(any(ByteBuffer.class)))
        .thenAnswer(
            inv -> {
              ByteBuffer buf = inv.getArgument(0);
              buf.put("chunk1".getBytes(StandardCharsets.UTF_8));
              return 6;
            });

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(endPoint, Executors.newSingleThreadExecutor(), tunnel);

    connection.onFillable();

    ByteBuffer received = tunnel.pollWrittenChunk();
    byte[] bytes = new byte[received.remaining()];
    received.get(bytes);
    assertEquals("chunk1", new String(bytes, StandardCharsets.UTF_8));

    connection.close();
  }

  @Test
  void downstreamPumpWritesToEndPoint() throws Exception {
    EndPoint endPoint = mock(EndPoint.class);
    TestTunnelStream tunnel = new TestTunnelStream();

    doAnswer(
            inv -> {
              Callback cb = inv.getArgument(0);
              cb.succeeded();
              return null;
            })
        .when(endPoint)
        .write(any(Callback.class), any(ByteBuffer.class));

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(endPoint, Executors.newSingleThreadExecutor(), tunnel);

    connection.onOpen();

    tunnel.pushReadChunk(ByteBuffer.wrap("from-tunnel".getBytes(StandardCharsets.UTF_8)));

    verify(endPoint).write(any(Callback.class), any(ByteBuffer.class));
    connection.close();
  }

  @Test
  void gracefulHalfClosePropagation() throws Exception {
    EndPoint endPoint = mock(EndPoint.class);
    TestTunnelStream tunnel = new TestTunnelStream();

    when(endPoint.fill(any(ByteBuffer.class))).thenReturn(-1);

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(endPoint, Executors.newSingleThreadExecutor(), tunnel);

    connection.onFillable();

    tunnel.pushEof();
    connection.close();
  }

  @Test
  void abortClosesBothSides() throws IOException {
    EndPoint endPoint = mock(EndPoint.class);
    TestTunnelStream tunnel = new TestTunnelStream();

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(endPoint, Executors.newSingleThreadExecutor(), tunnel);

    connection.close();
    verify(endPoint).close();
  }

  static class TestTunnelStream implements TunnelStream {
    final BlockingQueue<ByteBuffer> writtenChunks = new LinkedBlockingQueue<>();
    final BlockingQueue<CompletableFuture<ByteBuffer>> pendingReads = new LinkedBlockingQueue<>();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicBoolean endOutputCalled = new AtomicBoolean();

    @Override
    public CompletionStage<ByteBuffer> read() {
      CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
      pendingReads.add(future);
      return future;
    }

    @Override
    public CompletionStage<Void> write(ByteBuffer source) {
      byte[] copy = new byte[source.remaining()];
      source.get(copy);
      writtenChunks.add(ByteBuffer.wrap(copy));
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> endOutput() {
      endOutputCalled.set(true);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      closed.set(true);
    }

    ByteBuffer pollWrittenChunk() throws InterruptedException {
      return writtenChunks.poll(2, TimeUnit.SECONDS);
    }

    void pushReadChunk(ByteBuffer buf) throws InterruptedException {
      CompletableFuture<ByteBuffer> future = pendingReads.poll(2, TimeUnit.SECONDS);
      if (future != null) {
        future.complete(buf);
      }
    }

    void pushEof() throws InterruptedException {
      CompletableFuture<ByteBuffer> future = pendingReads.poll(2, TimeUnit.SECONDS);
      if (future != null) {
        future.complete(null);
      }
    }
  }
}
