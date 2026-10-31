package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
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
              BufferUtil.append(buf, ByteBuffer.wrap("chunk1".getBytes(StandardCharsets.UTF_8)));
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

  @Test
  void prefetchedBytesFailureAborts() {
    EndPoint endPoint = mock(EndPoint.class);
    TestTunnelStream tunnel = new TestTunnelStream();
    tunnel.nextWriteFailure = new IOException("write failed");

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(endPoint, Executors.newSingleThreadExecutor(), tunnel);

    connection.onUpgradeTo(ByteBuffer.wrap("data".getBytes(StandardCharsets.UTF_8)));
    connection.onOpen();

    verify(endPoint).close();
  }

  @Test
  void fillErrorScenarios() throws Exception {
    EndPoint endPoint = mock(EndPoint.class);
    TestTunnelStream tunnel = new TestTunnelStream();

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(endPoint, Executors.newSingleThreadExecutor(), tunnel);

    // 1. onFillable when closed returns early
    connection.close();
    connection.onFillable();

    // 2. onFillable read == 0 requests fillInterested
    EndPoint endPoint2 = mock(EndPoint.class);
    JettyUpgradeConnection connection2 =
        new JettyUpgradeConnection(endPoint2, Executors.newSingleThreadExecutor(), tunnel);
    when(endPoint2.fill(any(ByteBuffer.class))).thenReturn(0);
    connection2.onFillable();
    verify(endPoint2).fillInterested(any());

    // 3. onFillable fill throws IOException
    EndPoint endPoint3 = mock(EndPoint.class);
    TestTunnelStream tunnel3 = new TestTunnelStream();
    JettyUpgradeConnection connection3 =
        new JettyUpgradeConnection(endPoint3, Executors.newSingleThreadExecutor(), tunnel3);
    when(endPoint3.fill(any(ByteBuffer.class))).thenThrow(new IOException("conn reset"));
    connection3.onFillable();
    assertTrue(tunnel3.closed.get());

    // 4. onFillInterestedFailed
    EndPoint endPoint4 = mock(EndPoint.class);
    TestTunnelStream tunnel4 = new TestTunnelStream();
    JettyUpgradeConnection connection4 =
        new JettyUpgradeConnection(endPoint4, Executors.newSingleThreadExecutor(), tunnel4);
    connection4.onFillInterestedFailed(new IOException("interest failed"));
    assertTrue(tunnel4.closed.get());

    // 5. tunnel.write failure during onFillable
    EndPoint endPoint5 = mock(EndPoint.class);
    TestTunnelStream tunnel5 = new TestTunnelStream();
    tunnel5.nextWriteFailure = new IOException("tunnel write fail");
    when(endPoint5.fill(any(ByteBuffer.class)))
        .thenAnswer(
            inv -> {
              ByteBuffer buf = inv.getArgument(0);
              BufferUtil.append(buf, ByteBuffer.wrap("data".getBytes(StandardCharsets.UTF_8)));
              return 4;
            });
    JettyUpgradeConnection connection5 =
        new JettyUpgradeConnection(endPoint5, Executors.newSingleThreadExecutor(), tunnel5);
    connection5.onFillable();
    assertTrue(tunnel5.closed.get());

    // 6. graceful endOutput error aborts
    EndPoint endPoint6 = mock(EndPoint.class);
    TestTunnelStream tunnel6 = new TestTunnelStream();
    tunnel6.nextEndOutputFailure = new IOException("endOutput fail");
    when(endPoint6.fill(any(ByteBuffer.class))).thenReturn(-1);
    JettyUpgradeConnection connection6 =
        new JettyUpgradeConnection(endPoint6, Executors.newSingleThreadExecutor(), tunnel6);
    connection6.onFillable();
    verify(endPoint6).close();
  }

  static class TestTunnelStream implements TunnelStream {
    final BlockingQueue<ByteBuffer> writtenChunks = new LinkedBlockingQueue<>();
    final BlockingQueue<CompletableFuture<ByteBuffer>> pendingReads = new LinkedBlockingQueue<>();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicBoolean endOutputCalled = new AtomicBoolean();
    volatile Throwable nextWriteFailure;
    volatile Throwable nextEndOutputFailure;

    @Override
    public CompletionStage<ByteBuffer> read() {
      CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
      pendingReads.add(future);
      return future;
    }

    @Override
    public CompletionStage<Void> write(ByteBuffer source) {
      if (nextWriteFailure != null) {
        Throwable err = nextWriteFailure;
        nextWriteFailure = null;
        return CompletableFuture.failedFuture(err);
      }
      byte[] copy = new byte[source.remaining()];
      source.get(copy);
      writtenChunks.add(ByteBuffer.wrap(copy));
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> endOutput() {
      endOutputCalled.set(true);
      if (nextEndOutputFailure != null) {
        Throwable err = nextEndOutputFailure;
        nextEndOutputFailure = null;
        return CompletableFuture.failedFuture(err);
      }
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
