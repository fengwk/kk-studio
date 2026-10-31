package fun.fengwk.kkstudio.harness.environment.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

class DaemonEnvironmentTunnelTest {

  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(
          EnvironmentId.parse("3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f"), "inst-1");

  private TestSession session;
  private DaemonEnvironmentTunnel tunnel;

  @BeforeEach
  void setUp() {
    session = new TestSession();
    tunnel = new DaemonEnvironmentTunnel(IDENTITY, session, () -> false);
  }

  @Test
  void openHttpStreamsRequestAndResponseAndSkipsInformational1xx() throws Exception {
    TunnelHttpRequest request =
        new TunnelHttpRequest("POST", "/test", Map.of("x-custom", List.of("v1")));
    TunnelExchange exchange = tunnel.openHttp(request);

    assertEquals(1, session.streamsCreated.size());
    TestStream stream = session.streamsCreated.get(0);
    assertNotNull(stream);

    // 1. 发送 100 Continue 信息响应，不应该使 responseHeaders 完成
    MetaData.Response info100 =
        new MetaData.Response(100, "Continue", HttpVersion.HTTP_2, HttpFields.build());
    stream.listener.onHeaders(stream, new HeadersFrame(info100, null, false));
    assertFalse(exchange.responseHeaders().toCompletableFuture().isDone());

    // 2. 发送最终 200 响应
    HttpFields.Mutable respFields = HttpFields.build();
    respFields.add("content-type", "application/json");
    MetaData.Response respMeta = new MetaData.Response(200, "OK", HttpVersion.HTTP_2, respFields);
    stream.listener.onHeaders(stream, new HeadersFrame(respMeta, null, false));

    TunnelExchange.Response response = exchange.responseHeaders().toCompletableFuture().get();
    assertEquals(200, response.status());
    assertEquals(List.of("application/json"), response.headers().get("content-type"));

    // 写请求体并推进 position
    TunnelStream tunnelStream = exchange.stream();
    ByteBuffer writeBuf = ByteBuffer.wrap(new byte[] {1, 2, 3});
    CompletableFuture<Void> writeStage = tunnelStream.write(writeBuf).toCompletableFuture();
    assertEquals(3, writeBuf.position()); // 成功时 position 推进到 limit
    stream.completeLatestData();
    writeStage.get();

    // 3. 验证未 read 时不 demand（严格反压，杜绝无界预读）
    assertEquals(0, stream.demandCount.get());
    CompletableFuture<ByteBuffer> readStage = tunnelStream.read().toCompletableFuture();
    // 挂起 read 后才 demand 一次
    assertEquals(1, stream.demandCount.get());

    // 对端提供数据
    stream.inboundQueue.add(new TestData(ByteBuffer.wrap(new byte[] {9, 8}), false));
    stream.listener.onDataAvailable(stream);
    ByteBuffer readBuf = readStage.get();
    assertEquals(2, readBuf.remaining());
    assertEquals(9, readBuf.get());
    assertEquals(8, readBuf.get());

    // 读端正常 EOF
    stream.listener.onHeaders(stream, new HeadersFrame(1, null, null, true));
    assertNull(tunnelStream.read().toCompletableFuture().get());
    assertNull(tunnelStream.read().toCompletableFuture().get());
  }

  @Test
  void writesAndEndOutputBeforeStreamAttachQueueAndExecuteInOrder() throws Exception {
    session.autoAttach = false; // 延迟 attach

    TunnelHttpRequest request = new TunnelHttpRequest("POST", "/delayed", Map.of());
    TunnelExchange exchange = tunnel.openHttp(request);
    TunnelStream tunnelStream = exchange.stream();

    // 在 stream 尚未 attach 前调用 write
    ByteBuffer buf = ByteBuffer.wrap(new byte[] {10, 20});
    CompletableFuture<Void> writeStage = tunnelStream.write(buf).toCompletableFuture();
    assertEquals(2, buf.position()); // 必须推进 position
    assertFalse(writeStage.isDone());

    // 在 stream 尚未 attach 前调用 endOutput
    CompletableFuture<Void> endStage = tunnelStream.endOutput().toCompletableFuture();
    assertFalse(endStage.isDone());

    // 此时 attach stream
    TestStream stream = session.pendingStream;
    session.pendingPromise.succeeded(stream);

    // 此时第一个 write 已被发出
    assertEquals(1, stream.sentFrames.size());
    assertFalse(stream.sentFrames.get(0).isEndStream());
    stream.completeLatestData();
    writeStage.get(); // write 成功完成

    // 随后 endOutput 发送 END_STREAM 帧
    assertEquals(2, stream.sentFrames.size());
    assertTrue(stream.sentFrames.get(1).isEndStream());
    stream.completeLatestData();
    endStage.get(); // endOutput 成功完成
  }

  @Test
  void largeDataStreamingRoundtrip() throws Exception {
    TunnelHttpRequest request = new TunnelHttpRequest("POST", "/large", Map.of());
    TunnelExchange exchange = tunnel.openHttp(request);
    TestStream stream = session.streamsCreated.get(0);
    TunnelStream tunnelStream = exchange.stream();

    // 发送 500 KiB 数据
    byte[] bigData = new byte[500 * 1024];
    for (int i = 0; i < bigData.length; i++) {
      bigData[i] = (byte) (i & 0xff);
    }
    ByteBuffer buf = ByteBuffer.wrap(bigData);
    CompletableFuture<Void> writeFuture = tunnelStream.write(buf).toCompletableFuture();
    assertEquals(bigData.length, buf.position());
    stream.completeLatestData();
    writeFuture.get();

    // 接收 500 KiB 数据
    CompletableFuture<ByteBuffer> readFuture = tunnelStream.read().toCompletableFuture();
    stream.inboundQueue.add(new TestData(ByteBuffer.wrap(bigData), false));
    stream.listener.onDataAvailable(stream);
    ByteBuffer received = readFuture.get();
    assertEquals(bigData.length, received.remaining());
  }

  @Test
  void openWebSocketRequiresGetAndConnectProtocol() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> tunnel.openWebSocket(new TunnelHttpRequest("POST", "/ws", Map.of())));

    TunnelHttpRequest request =
        new TunnelHttpRequest("GET", "/ws", Map.of("sec-websocket-key", List.of("k1")));
    TunnelExchange exchange = tunnel.openWebSocket(request);

    TestStream stream = session.streamsCreated.get(0);
    assertTrue(stream.headersFrame.getMetaData() instanceof MetaData.ConnectRequest);
    MetaData.ConnectRequest connect = (MetaData.ConnectRequest) stream.headersFrame.getMetaData();
    assertEquals("websocket", connect.getProtocol());
    assertEquals("CONNECT", connect.getMethod());

    MetaData.Response respMeta =
        new MetaData.Response(200, "OK", HttpVersion.HTTP_2, HttpFields.build());
    stream.listener.onHeaders(stream, new HeadersFrame(respMeta, null, false));
    assertEquals(200, exchange.responseHeaders().toCompletableFuture().get().status());
  }

  @Test
  void enforcesSinglePendingPerDirection() {
    TunnelHttpRequest request = new TunnelHttpRequest("GET", "/single", Map.of());
    TunnelExchange exchange = tunnel.openHttp(request);
    TunnelStream stream = exchange.stream();

    CompletableFuture<ByteBuffer> r1 = stream.read().toCompletableFuture();
    assertFalse(r1.isDone());
    assertThrows(IllegalStateException.class, stream::read);

    ByteBuffer buf = ByteBuffer.wrap(new byte[] {1});
    CompletableFuture<Void> w1 = stream.write(buf).toCompletableFuture();
    assertThrows(IllegalStateException.class, () -> stream.write(ByteBuffer.wrap(new byte[] {2})));
  }

  @Test
  void closeCancelsPendingOperations() {
    TunnelHttpRequest request = new TunnelHttpRequest("GET", "/cancel", Map.of());
    TunnelExchange exchange = tunnel.openHttp(request);
    TunnelStream stream = exchange.stream();

    CompletableFuture<ByteBuffer> r1 = stream.read().toCompletableFuture();
    exchange.close();
    assertThrows(CancellationException.class, r1::get);
  }

  @Test
  void sessionClosedFailsActiveExchanges() {
    TunnelHttpRequest request = new TunnelHttpRequest("GET", "/fail", Map.of());
    TunnelExchange exchange = tunnel.openHttp(request);
    TunnelStream stream = exchange.stream();

    CompletableFuture<ByteBuffer> r1 = stream.read().toCompletableFuture();
    tunnel.onSessionClosed(new IOException("conn broke"));

    ExecutionException err = assertThrows(ExecutionException.class, r1::get);
    assertTrue(err.getCause() instanceof IOException);
    assertThrows(IllegalStateException.class, () -> tunnel.openHttp(request));
  }

  private static final class TestSession implements Session {
    private final List<TestStream> streamsCreated = new ArrayList<>();
    private boolean autoAttach = true;
    private TestStream pendingStream;
    private Promise<Stream> pendingPromise;

    @Override
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener) {
      TestStream stream = new TestStream(frame, listener);
      streamsCreated.add(stream);
      if (autoAttach) {
        promise.succeeded(stream);
      } else {
        pendingStream = stream;
        pendingPromise = promise;
      }
    }

    @Override
    public int priority(PriorityFrame frame, Callback callback) {
      return 0;
    }

    @Override
    public void settings(SettingsFrame frame, Callback callback) {}

    @Override
    public void ping(PingFrame frame, Callback callback) {}

    @Override
    public boolean close(int error, String reason, Callback callback) {
      return true;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public boolean isPushEnabled() {
      return false;
    }

    @Override
    public Collection<Stream> getStreams() {
      return List.of();
    }

    @Override
    public Stream getStream(int streamId) {
      return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public CompletableFuture<Void> shutdown() {
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class TestData extends Stream.Data {
    TestData(ByteBuffer buffer, boolean endStream) {
      super(new DataFrame(1, buffer, endStream));
    }
  }

  private static final class TestStream implements Stream {
    private final HeadersFrame headersFrame;
    private final Stream.Listener listener;
    private final Queue<TestData> inboundQueue = new ArrayDeque<>();
    private final Queue<Callback> writeCallbacks = new ArrayDeque<>();
    private final List<DataFrame> sentFrames = new ArrayList<>();
    private final AtomicInteger demandCount = new AtomicInteger();

    TestStream(HeadersFrame headersFrame, Stream.Listener listener) {
      this.headersFrame = headersFrame;
      this.listener = listener;
    }

    @Override
    public int getId() {
      return 1;
    }

    @Override
    public Stream.Data readData() {
      return inboundQueue.poll();
    }

    @Override
    public void demand() {
      demandCount.incrementAndGet();
    }

    @Override
    public void data(DataFrame frame, Callback callback) {
      sentFrames.add(frame);
      writeCallbacks.add(callback);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback) {
      callback.succeeded();
    }

    void completeLatestData() {
      Callback cb = writeCallbacks.poll();
      if (cb != null) {
        cb.succeeded();
      }
    }

    @Override
    public Stream.Listener getListener() {
      return listener;
    }

    @Override
    public Session getSession() {
      return null;
    }

    @Override
    public void headers(HeadersFrame frame, Callback callback) {}

    @Override
    public void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener) {}

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public Object removeAttribute(String name) {
      return null;
    }

    @Override
    public boolean isLocal() {
      return true;
    }

    @Override
    public boolean isReset() {
      return false;
    }

    @Override
    public boolean isRemotelyClosed() {
      return false;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public long getIdleTimeout() {
      return 0;
    }

    @Override
    public void setIdleTimeout(long idleTimeout) {}
  }
}
