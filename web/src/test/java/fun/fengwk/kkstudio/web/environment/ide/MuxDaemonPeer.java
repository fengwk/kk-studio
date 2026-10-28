package fun.fengwk.kkstudio.web.environment.ide;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import fun.fengwk.kkstudio.ide.mux.MuxDataKind;
import fun.fengwk.kkstudio.ide.mux.MuxFrame;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxHeaders;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.ide.mux.MuxMode;
import fun.fengwk.kkstudio.ide.mux.MuxOpen;
import fun.fengwk.kkstudio.ide.mux.MuxWireCodec;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 WebSocket 上的 Daemon 侧 mux peer，用于验证 Server 适配器的物理帧读写。
 *
 * <p>只实现测试所需的最小 Daemon 角色：接受 OPEN、回 READY/HEADERS，HTTP 在请求 END 后返回固定响应，WS 原样回显 DATA 分段。所有写入保持
 * 单线程顺序，避免测试替身掩盖 wire 顺序问题。
 */
final class MuxDaemonPeer implements AutoCloseable {

  private final MuxWireCodec codec = new MuxWireCodec();
  private final OkHttpClient client = new OkHttpClient();
  private final Map<Long, StreamState> streams = new ConcurrentHashMap<>();
  private final List<MuxFrame.Open> opens = Collections.synchronizedList(new ArrayList<>());
  private final List<WsSegment> wsSegments = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger connectionCount = new AtomicInteger();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CountDownLatch connected = new CountDownLatch(1);
  private final CountDownLatch completed = new CountDownLatch(1);
  private final Object sendLock = new Object();

  private volatile WebSocket socket;

  void dial(URI httpUrl) {
    Objects.requireNonNull(httpUrl, "httpUrl");
    String scheme = "https".equalsIgnoreCase(httpUrl.getScheme()) ? "wss" : "ws";
    URI webSocketUri =
        URI.create(
            scheme
                + "://"
                + httpUrl.getAuthority()
                + httpUrl.getRawPath()
                + (httpUrl.getRawQuery() == null ? "" : "?" + httpUrl.getRawQuery()));
    client.newWebSocket(
        new Request.Builder().url(webSocketUri.toString()).build(),
        new WebSocketListener() {
          @Override
          public void onOpen(WebSocket webSocket, Response response) {
            socket = webSocket;
            connectionCount.incrementAndGet();
            connected.countDown();
          }

          @Override
          public void onMessage(WebSocket webSocket, ByteString bytes) {
            receive(bytes.toByteArray());
          }

          @Override
          public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            failure.compareAndSet(null, error);
            connected.countDown();
          }

          @Override
          public void onClosed(WebSocket webSocket, int code, String reason) {
            connected.countDown();
          }
        });
  }

  boolean awaitConnected(long seconds) throws InterruptedException {
    return connected.await(seconds, TimeUnit.SECONDS);
  }

  boolean awaitCompleted(long seconds) throws InterruptedException {
    return completed.await(seconds, TimeUnit.SECONDS);
  }

  List<MuxFrame.Open> opens() {
    synchronized (opens) {
      return List.copyOf(opens);
    }
  }

  List<WsSegment> wsSegments() {
    synchronized (wsSegments) {
      return List.copyOf(wsSegments);
    }
  }

  int connectionCount() {
    return connectionCount.get();
  }

  Throwable failure() {
    return failure.get();
  }

  private void receive(byte[] wire) {
    try {
      MuxFrame frame = codec.decode(wire);
      switch (frame) {
        case MuxFrame.Open open -> onOpen(open);
        case MuxFrame.Data data -> onData(data);
        case MuxFrame.End end -> onEnd(end);
        case MuxFrame.Window ignored -> {
          // 测试响应体远小于初始 credit；保留窗口帧只用于验证 wire 可解码。
        }
        case MuxFrame.Complete ignored -> completed.countDown();
        case MuxFrame.Close close -> {
          streams.remove(close.streamId());
          send(new MuxFrame.Close(close.streamId(), close.close()));
        }
        case MuxFrame.Reset reset -> streams.remove(reset.streamId());
        case MuxFrame.Ready ready -> {
          // Daemon 角色不接收 READY。
        }
        case MuxFrame.Headers headers -> {
          // Daemon 角色不接收 HEADERS。
        }
      }
    } catch (RuntimeException error) {
      failure.compareAndSet(null, error);
      WebSocket current = socket;
      if (current != null) {
        current.cancel();
      }
    }
  }

  private void onOpen(MuxFrame.Open open) {
    opens.add(open);
    streams.put(open.streamId(), new StreamState(open.request()));
    send(new MuxFrame.Ready(open.streamId()));
    if (open.request().mode() == MuxMode.WS) {
      send(new MuxFrame.Headers(open.streamId(), MuxHeaders.webSocket(List.of())));
    }
  }

  private void onData(MuxFrame.Data data) {
    StreamState state = streams.get(data.streamId());
    if (state == null) {
      return;
    }
    // 每个真实 DATA 都消费后归还 Server 的发送 credit，避免测试用大请求体时停住。
    send(new MuxFrame.Window(data.streamId(), MuxLimits.dataCost(data.payload().length)));
    if (state.request.mode() == MuxMode.WS) {
      wsSegments.add(
          new WsSegment(data.kind() == MuxDataKind.WS_TEXT, data.fin(), data.payload().length));
      send(new MuxFrame.Data(data.streamId(), data.kind(), data.fin(), data.payload()));
      return;
    }
    state.requestBody.write(data.payload(), 0, data.payload().length);
  }

  private void onEnd(MuxFrame.End end) {
    StreamState state = streams.get(end.streamId());
    if (state == null || state.responded) {
      return;
    }
    state.responded = true;
    byte[] body =
        state.request.path().contains("/echo")
            ? state.requestBody.toByteArray()
            : "hello".getBytes(StandardCharsets.UTF_8);
    List<MuxHeader> responseHeaders = new ArrayList<>();
    responseHeaders.add(new MuxHeader("content-type", "text/plain"));
    int status = 200;
    if (state.request.path().contains("/echo")) {
      responseHeaders.add(new MuxHeader("set-cookie", "upstream=forbidden; Path=/"));
    }
    if (state.request.path().contains("/redirect")) {
      // 真实 code-server 在内部挂载下返回带 `/__kk/ide/view/...` 的 Location。
      status = 302;
      responseHeaders.add(
          new MuxHeader(
              "location",
              "/__kk/ide/view/"
                  + state.request.viewId()
                  + "/"
                  + state.request.bindingEpoch()
                  + "/target?x=1"));
    }
    send(new MuxFrame.Headers(end.streamId(), MuxHeaders.http(status, responseHeaders)));
    send(new MuxFrame.Data(end.streamId(), MuxDataKind.HTTP, false, body));
    send(new MuxFrame.End(end.streamId()));
  }

  private void send(MuxFrame frame) {
    synchronized (sendLock) {
      WebSocket current = socket;
      if (current == null) {
        throw new IllegalStateException("daemon peer is not connected");
      }
      if (!current.send(ByteString.of(codec.encode(frame)))) {
        throw new IllegalStateException("daemon peer send queue rejected a frame");
      }
    }
  }

  @Override
  public void close() {
    WebSocket current = socket;
    if (current != null) {
      current.close(1000, "test complete");
    }
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  record WsSegment(boolean text, boolean fin, int bytes) {}

  private static final class StreamState {

    private final MuxOpen request;
    private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
    private boolean responded;

    private StreamState(MuxOpen request) {
      this.request = request;
    }
  }
}
