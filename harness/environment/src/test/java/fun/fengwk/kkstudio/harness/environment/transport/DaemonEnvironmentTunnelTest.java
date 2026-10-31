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
  void openHttpStreamsRequestAndResponse() throws Exception {
    TunnelHttpRequest request =
        new TunnelHttpRequest("POST", "/test", Map.of("x-custom", List.of("v1")));
    TunnelExchange exchange = tunnel.openHttp(request);

    assertEquals(1, session.streamsCreated.size());
    TestStream stream = session.streamsCreated.get(0);
    assertNotNull(stream);

    // 模拟服务端响应 200 与响应头
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

    // 读响应体
    stream.inboundQueue.add(new TestData(ByteBuffer.wrap(new byte[] {9, 8}), false));
    stream.listener.onDataAvailable(stream);
    ByteBuffer readBuf = tunnelStream.read().toCompletableFuture().get();
    assertEquals(2, readBuf.remaining());
    assertEquals(9, readBuf.get());
    assertEquals(8, readBuf.get());

    // 读端正常 EOF
    stream.listener.onHeaders(stream, new HeadersFrame(1, null, null, true));
    assertNull(tunnelStream.read().toCompletableFuture().get());
    // 后续继续完成 null
    assertNull(tunnelStream.read().toCompletableFuture().get());
  }

  @Test
  void openWebSocketRequiresGetAndConnectProtocol() throws Exception {
    // 非 GET 应抛出 IllegalArgumentException
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

    // 200 响应建立隧道
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

    // 第一个 read pending
    CompletableFuture<ByteBuffer> r1 = stream.read().toCompletableFuture();
    assertFalse(r1.isDone());

    // 第二个 read 同步抛 IllegalStateException
    assertThrows(IllegalStateException.class, stream::read);

    // 写一个 pending
    ByteBuffer buf = ByteBuffer.wrap(new byte[] {1});
    CompletableFuture<Void> w1 = stream.write(buf).toCompletableFuture();

    // 第二个 write 同步抛 IllegalStateException
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

    @Override
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener) {
      TestStream stream = new TestStream(frame, listener);
      streamsCreated.add(stream);
      promise.succeeded(stream);
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
    public void demand() {}

    @Override
    public void data(DataFrame frame, Callback callback) {
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
