package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
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

/**
 * IDE 反向数据面端点的端到端网络契约：真实 Tomcat 上由真实 WebSocket Daemon peer 传输 20 MiB HTTP 响应体。
 *
 * <p>与 {@link IdeGatewayDataPlaneTest} 的纯组件测试互补：这里走真实 servlet 过滤器（host guard）、真实反向 WebSocket 握手与
 * ticket 消费、真实 credit 往返，验证「浏览器 HTTP → 网关 → 反向 WS → Daemon」整条链路，而不仅是状态机。
 */
class IdeReverseStreamEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static final int BODY_BYTES = 20 * 1024 * 1024;

  @DynamicPropertySource
  static void ideGatewayProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "kk-studio.harness.ide-gateway.stream-url-template",
        () -> "wss://gateway.test" + IdeGatewayProperties.STREAM_PATH);
    registry.add("kk-studio.harness.ide.origin-template", () -> "https://{environmentId}.ide.test");
  }

  @LocalServerPort private int port;

  @MockitoBean private EnvironmentDaemonServer endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  /** 20 MiB 响应体经真实反向 WS 传输；credit 窗口只有 256 KiB，因此 ACK 往返必须真实生效。 */
  @Test
  void streamsTwentyMiBBodyThroughRealReverseWebSocket() throws Exception {
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    FakeDaemon daemon = new FakeDaemon();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              daemon.dial(streamUrl(command.ticket()));
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });

    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    OkHttpClient browser = localhostClient();
    try {
      Response response =
          browser
              .newCall(
                  new Request.Builder()
                      .url("http://" + ENVIRONMENT + ".ide.test:" + port + "/workspace/file.txt")
                      .header("Cookie", cookieHeader(cookie))
                      .build())
              .execute();
      byte[] body = response.body().bytes();
      assertEquals(200, response.code());
      assertEquals(BODY_BYTES, body.length);
      assertEquals(expectedByte(BODY_BYTES - 1), body[BODY_BYTES - 1]);
      assertEquals(expectedByte(1234), body[1234]);
      assertEquals("text/plain", response.header("Content-Type"));
      assertNull(
          response.header("Proxy-Authenticate"), "hop-by-hop header must not reach the browser");
      assertNull(response.header("TE"), "hop-by-hop header must not reach the browser");
      assertTrue(daemon.sawRequestEnd.await(5, TimeUnit.SECONDS), "daemon must observe HTTP END");
      assertTrue(daemon.completed.await(5, TimeUnit.SECONDS), "daemon must finish its response");
      verify(endpoint, timeout(5_000)).abandonIde(any());
    } finally {
      shutdown(browser);
      daemon.awaitStopped();
    }
  }

  /** 未知/已消费 ticket 的反向握手必须被策略违规关闭，且不产生任何数据面副作用。 */
  @Test
  void rejectsUnknownStreamTicket() throws Exception {
    OkHttpClient daemon = new OkHttpClient();
    CountDownLatch closed = new CountDownLatch(1);
    try {
      WebSocket socket =
          daemon.newWebSocket(
              new Request.Builder()
                  .url(
                      "ws://localhost:"
                          + port
                          + IdeGatewayProperties.STREAM_PATH
                          + "/"
                          + "unknown-ticket")
                  .build(),
              new WebSocketListener() {
                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                  assertEquals(1008, code);
                  closed.countDown();
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                  assertEquals(1008, code);
                  closed.countDown();
                  webSocket.close(code, reason);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                  if (response != null) {
                    assertEquals(1008, response.code());
                  }
                  closed.countDown();
                }
              });
      assertTrue(closed.await(15, TimeUnit.SECONDS), "unknown ticket handshake must be closed");
      socket.cancel();
    } finally {
      shutdown(daemon);
    }
  }

  private String streamUrl(String ticket) {
    return "ws://localhost:"
        + port
        + IdeGatewayProperties.STREAM_PATH
        + "/"
        + ticket
        + "?environment="
        + ENVIRONMENT;
  }

  private String cookieHeader(String cookie) {
    return gatewayProperties.requireSessionCookieName() + "=" + cookie;
  }

  private OkHttpClient localhostClient() {
    return new OkHttpClient.Builder()
        .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
        .build();
  }

  private static void shutdown(OkHttpClient client) {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  private static byte expectedByte(int index) {
    return (byte) (index * 31 + 7);
  }

  /** 严格按 credit 窗口发送 20 MiB 响应的最小 Daemon peer。 */
  private final class FakeDaemon extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private final CountDownLatch sawRequestEnd = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private final Thread worker;

    FakeDaemon() {
      this.worker = new Thread(this::serve, "fake-ide-daemon");
      this.worker.setDaemon(true);
    }

    void dial(String streamUrl) {
      client = new OkHttpClient();
      client.newWebSocket(new Request.Builder().url(streamUrl).build(), this);
      worker.start();
    }

    private void serve() {
      try {
        awaitFirstFrame();
        sawRequestEnd.countDown();
        sendText(
            "{\"type\":\"HTTP_RESPONSE\",\"status\":200,\"headers\":"
                + "[[\"content-type\",\"text/plain\"],[\"content-length\",\""
                + BODY_BYTES
                + "\"],[\"proxy-authenticate\",\"Basic realm=hop\"],[\"te\",\"trailers\"]]}");
        byte[] chunk = new byte[DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES];
        int sent = 0;
        while (sent < BODY_BYTES) {
          int size = Math.min(chunk.length, BODY_BYTES - sent);
          for (int i = 0; i < size; i++) {
            chunk[i] = expectedByte(sent + i);
          }
          byte[] payload = new byte[size];
          System.arraycopy(chunk, 0, payload, 0, size);
          while (!window.canSend(size)) {
            drainAcks();
          }
          window.onSend(size);
          sendBinary(codec.encodeHttpBody(payload));
          sent += size;
        }
        sendBinary(codec.encodeHttpEnd());
        completed.countDown();
      } catch (Exception error) {
        // 失败由上层断言（completed 不会 countDown）暴露，避免静默通过。
      }
    }

    private void awaitFirstFrame() throws InterruptedException {
      byte[] frame = inbound.poll(15, TimeUnit.SECONDS);
      assertNotNull(frame, "daemon must receive the HTTP END frame");
      assertEquals(2, frame[0], "GET exchange must arrive as a bare HTTP END");
    }

    private void drainAcks() throws InterruptedException {
      byte[] frame = inbound.poll(15, TimeUnit.SECONDS);
      assertNotNull(frame, "daemon must receive credit ACKs while streaming");
      assertEquals(3, frame[0], "expected an ACK frame");
      long byteCount =
          ((long) (frame[1] & 0xFF) << 24)
              | ((long) (frame[2] & 0xFF) << 16)
              | ((long) (frame[3] & 0xFF) << 8)
              | (long) (frame[4] & 0xFF);
      window.onAck(byteCount);
    }

    private void sendText(String json) {
      socket.send(json);
    }

    private void sendBinary(byte[] frame) {
      socket.send(ByteString.of(frame));
    }

    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    void awaitStopped() throws InterruptedException {
      worker.join(Duration.ofSeconds(15).toMillis());
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
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      inbound.add(bytes.toByteArray());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      inbound.add(new byte[] {99});
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      inbound.add(new byte[] {99});
    }
  }

  /** bootstrap 消费的真实前置：先由真实 OPEN_VIEW 授权视图，再消费一次性票据换 cookie。 */
  private String openSession(IdeSessionIdentity identity, String viewId, String workspacePath) {
    sessions.viewOpened(identity, viewId, workspacePath);
    return sessions.open(identity, viewId, workspacePath);
  }
}
