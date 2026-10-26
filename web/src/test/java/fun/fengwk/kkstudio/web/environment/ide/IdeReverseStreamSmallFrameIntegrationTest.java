package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamBinaryFrame;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 回归：大量小帧必须完整到达浏览器。
 *
 * <p>code-server 的终端输出、RPC 与文件事件多是小消息。credit 曾只按字节计价，而 Gateway 的入站队列另有帧数上限：守约的 Daemon
 * 在一个字节窗口内发出上千个小帧， Gateway 就以 1009 断开反向流，浏览器看到截断的响应或 1011。现在每帧至少消耗一个最小 credit，守约的 Daemon 在窗口内至多有
 * {@link IdeStreamCreditWindow#MAX_IN_FLIGHT_FRAMES} 个在途帧，Gateway 的入站上界由此推导，同样的流量必须完整交付。
 */
class IdeReverseStreamSmallFrameIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  /** 远超一个窗口的帧数：必须经过多轮 ACK 才能发完。 */
  private static final int FRAMES = 4000;

  private static final int CHUNK = 16;

  @LocalServerPort private int port;

  @MockitoBean private IdeCommandTransport endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  @Test
  void deliversThousandsOfSmallHttpBodyFrames() throws Exception {
    SmallFrameDaemon daemon =
        new SmallFrameDaemon(
            (peer) -> {
              peer.awaitRequestEnd();
              peer.sendText(
                  "{\"type\":\"HTTP_RESPONSE\",\"status\":200,\"headers\":"
                      + "[[\"content-type\",\"text/plain\"],[\"content-length\",\""
                      + (FRAMES * CHUNK)
                      + "\"]]}");
              for (int i = 0; i < FRAMES; i++) {
                peer.sendData(peer.codec.encodeHttpBody(new byte[CHUNK]), CHUNK);
              }
              peer.sendBinary(peer.codec.encodeHttpEnd());
            });
    String cookie = openSession(daemon, "view-http");
    OkHttpClient browser = localhostClient();
    try (Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(baseUrl("http") + "/workspace/file.txt")
                    .header("Cookie", cookieHeader(cookie))
                    .build())
            .execute()) {
      assertEquals(200, response.code());
      assertEquals(FRAMES * CHUNK, response.body().bytes().length);
    } finally {
      shutdown(browser);
      daemon.awaitStopped();
    }
    assertNull(daemon.failure.get(), "the daemon must never be cut off");
  }

  @Test
  void deliversThousandsOfSmallWebSocketSegments() throws Exception {
    SmallFrameDaemon daemon =
        new SmallFrameDaemon(
            (peer) -> {
              peer.sendText(peer.codec.encodeWsReady());
              for (int i = 0; i < FRAMES; i++) {
                peer.sendData(peer.codec.encodeWsSegment(false, true, new byte[CHUNK]), CHUNK);
              }
            });
    String cookie = openSession(daemon, "view-ws");
    OkHttpClient browserClient = localhostClient();
    AtomicInteger received = new AtomicInteger();
    AtomicInteger browserClose = new AtomicInteger(-1);
    CountDownLatch done = new CountDownLatch(1);
    WebSocket browser =
        browserClient.newWebSocket(
            new Request.Builder()
                .url(baseUrl("ws") + "/socket")
                .header("Cookie", cookieHeader(cookie))
                .header("Origin", "http://localhost:" + port)
                .build(),
            new WebSocketListener() {
              @Override
              public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (received.incrementAndGet() == FRAMES) {
                  done.countDown();
                }
              }

              @Override
              public void onClosing(WebSocket webSocket, int code, String reason) {
                browserClose.compareAndSet(-1, code);
                done.countDown();
              }

              @Override
              public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                done.countDown();
              }
            });
    try {
      assertTrue(done.await(30, TimeUnit.SECONDS), "browser must receive every segment");
      assertEquals(FRAMES, received.get());
      assertEquals(-1, browserClose.get(), "the gateway must not close the browser mid-stream");
    } finally {
      browser.close(1000, null);
      shutdown(browserClient);
      daemon.awaitStopped();
    }
    assertNull(daemon.failure.get(), "the daemon must never be cut off");
  }

  private String openSession(SmallFrameDaemon daemon, String viewId) {
    DaemonConnectionIdentity identity =
        new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              daemon.dial(
                  "ws://localhost:"
                      + port
                      + IdeGatewayProperties.STREAM_PATH
                      + "/"
                      + command.ticket()
                      + "?environment="
                      + ENVIRONMENT);
              return new IdeRequest(
                  identity,
                  UUID.randomUUID(),
                  CompletableFuture.completedFuture(IdeReply.ok(null, null, null)));
            });
    sessions.viewOpened(identity, viewId, "/home/dev/project");
    return sessions.open(identity, viewId, "/home/dev/project");
  }

  private String baseUrl(String scheme) {
    return scheme + "://localhost:" + port + "/environment-ide/" + ENVIRONMENT;
  }

  private String cookieHeader(String cookie) {
    return gatewayProperties.requireSessionCookieName() + "=" + cookie;
  }

  private static OkHttpClient localhostClient() {
    return new OkHttpClient.Builder()
        .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
        .readTimeout(Duration.ofSeconds(30))
        .build();
  }

  private static void shutdown(OkHttpClient client) {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  /** 严格按帧 credit 发送大量小帧、并消费 Gateway ACK 的守约 Daemon peer。 */
  private static final class SmallFrameDaemon extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private final CountDownLatch opened = new CountDownLatch(1);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final Consumer<SmallFrameDaemon> script;
    private final Thread worker;
    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    SmallFrameDaemon(Consumer<SmallFrameDaemon> script) {
      this.script = script;
      this.worker = new Thread(this::serve, "small-frame-daemon");
      this.worker.setDaemon(true);
    }

    void dial(String streamUrl) {
      client = new OkHttpClient();
      client.newWebSocket(new Request.Builder().url(streamUrl).build(), this);
      worker.start();
    }

    private void serve() {
      try {
        if (!opened.await(15, TimeUnit.SECONDS)) {
          failure.compareAndSet(null, "reverse stream did not open");
          return;
        }
        script.accept(this);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException error) {
        failure.compareAndSet(null, error.toString());
      }
    }

    /** 等待 GET 的请求 END（期间到达的 ACK 照常释放 credit）。 */
    void awaitRequestEnd() {
      while (!(receive() instanceof IdeStreamBinaryFrame.HttpEnd)) {
        // 只有 ACK 会先于请求 END 到达，已在 receive 中处理。
      }
    }

    /** 按帧 credit 发送一个数据帧：窗口不足时先消费 Gateway 的 ACK。 */
    void sendData(byte[] frame, int payloadBytes) {
      while (!window.canSendFrame(payloadBytes)) {
        receive();
      }
      window.onSendFrame(payloadBytes);
      sendBinary(frame);
    }

    /** 取下一个入站二进制帧；ACK 当即释放发送 credit。 */
    private IdeStreamBinaryFrame receive() {
      byte[] frame;
      try {
        frame = inbound.poll(15, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(error);
      }
      if (frame == null || frame.length == 0) {
        throw new IllegalStateException("gateway stopped before the exchange completed");
      }
      IdeStreamBinaryFrame decoded = codec.decodeBinary(frame);
      if (decoded instanceof IdeStreamBinaryFrame.Ack ack) {
        window.onAck(ack.byteCount());
      }
      return decoded;
    }

    void sendText(String json) {
      socket.send(json);
    }

    void sendBinary(byte[] frame) {
      socket.send(ByteString.of(frame));
    }

    void awaitStopped() throws InterruptedException {
      worker.join(Duration.ofSeconds(30).toMillis());
      WebSocket current = socket;
      if (current != null) {
        current.cancel();
      }
      OkHttpClient currentClient = client;
      if (currentClient != null) {
        shutdown(currentClient);
      }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      this.socket = webSocket;
      opened.countDown();
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      inbound.add(bytes.toByteArray());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      if (code != 1000) {
        failure.compareAndSet(null, "gateway closed the reverse stream with " + code);
      }
      inbound.add(new byte[0]);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      inbound.add(new byte[0]);
    }
  }
}
