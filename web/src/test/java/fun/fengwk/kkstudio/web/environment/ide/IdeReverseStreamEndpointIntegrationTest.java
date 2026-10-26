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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IDE 反向数据面端点的端到端网络契约：真实 Tomcat 上由真实 WebSocket Daemon peer 传输 20 MiB HTTP 响应体， 以及通过真实 Node 24 进程运行
 * Daemon 传输模块加载与交付流式 CSS。
 *
 * <p>Node 进程测试前置：本仓 {@code daemon} 模块已完成 {@code npm ci} 与 {@code npm run build} 生成 {@code dist}；系统
 * PATH 上存在 Node 24（或通过环境变量 {@code NODE_BIN} / 系统属性 {@code node.bin} 覆盖）。
 *
 * <p>慢读取只验证非突发消费下的完整交付；操作系统和 Tomcat 缓冲使其不能代表稳定的生产反压。
 */
class IdeReverseStreamEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static final int BODY_BYTES = 20 * 1024 * 1024;

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @MockitoBean private IdeCommandTransport endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  /** 20 MiB 响应体经真实反向 WS 传输；credit 窗口只有 256 KiB，因此 ACK 往返必须真实生效。 */
  @Test
  void streamsTwentyMiBBodyThroughRealReverseWebSocket() throws Exception {
    DaemonConnectionIdentity identity =
        new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    FakeDaemon daemon = new FakeDaemon();
    UUID invocationId = UUID.randomUUID();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              daemon.dial(streamUrl(command.ticket()));
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, invocationId, reply);
            });

    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    OkHttpClient browser = localhostClient();
    try {
      Response response =
          browser
              .newCall(
                  new Request.Builder()
                      .url(
                          "http://localhost:"
                              + port
                              + "/environment-ide/"
                              + ENVIRONMENT
                              + "/workspace/file.txt")
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
      // 只核对本用例的调用：前序用例失败时遗留的异步 abandon 可能在 mock 重置后才到达。
      verify(endpoint, timeout(5_000)).abandonIde(invocationId);
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

  /** 约 204KB Brotli CSS 在 256 KiB credit 窗口内完整交付。 */
  @Test
  void streamsBrotliCssAbout204KbThroughRealNodeProcess() throws Exception {
    runNodeCss("204k", false);
  }

  /** 约 307KB Brotli CSS 跨越 credit 窗口后完整交付。 */
  @Test
  void streamsBrotliCssCrossing256KibWindowAbout307KbThroughRealNodeProcess() throws Exception {
    runNodeCss("307k", false);
  }

  /** 约 307KB Brotli CSS 在慢读取下完整交付。 */
  @Test
  void streamsBrotliCssCrossing256KibWindowUnderControlledSlowBrowserRead() throws Exception {
    runNodeCss("307k", true);
  }

  private void runNodeCss(String targetSize, boolean slow) throws Exception {
    DaemonConnectionIdentity identity =
        new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    String requestPath = "/out/vs/code/browser/workbench/workbench.css";
    AtomicReference<NodeExecution> nodeRef = new AtomicReference<>();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              String streamUrl = streamUrl(command.ticket());
              NodeExecution node = startNodeFixture(streamUrl, requestPath, targetSize);
              nodeRef.set(node);
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });

    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    OkHttpClient browser = localhostClient();
    try {
      byte[] body = null;
      Exception httpFailure = null;
      try {
        try (Response response =
            browser
                .newCall(
                    new Request.Builder()
                        .url(
                            "http://localhost:"
                                + port
                                + "/environment-ide/"
                                + ENVIRONMENT
                                + requestPath)
                        .header("Cookie", cookieHeader(cookie))
                        .header("Accept-Encoding", "br")
                        .build())
                .execute()) {
          assertEquals(200, response.code());
          assertEquals("text/css", response.header("Content-Type"));
          assertEquals("br", response.header("Content-Encoding"));
          assertNotNull(response.body(), "response body must not be null");
          if (slow) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = response.body().byteStream()) {
              int read;
              while ((read = in.read(buf)) != -1) {
                baos.write(buf, 0, read);
                Thread.sleep(10);
              }
            }
            body = baos.toByteArray();
          } else {
            body = response.body().bytes();
          }
        }
      } catch (Exception error) {
        httpFailure = error;
      }

      NodeExecution node = nodeRef.get();
      assertNotNull(node, "node fixture must have started");
      JsonNode report;
      try {
        report = node.awaitReport(MAPPER);
      } catch (Exception diagnosticError) {
        if (httpFailure != null) {
          httpFailure.addSuppressed(diagnosticError);
          throw httpFailure;
        }
        throw diagnosticError;
      }
      if (httpFailure != null) {
        throw httpFailure;
      }
      assertNotNull(body, "response body must have been read");
      assertTrue(report.get("exchangeOk").asBoolean(), "exchangeOk must be true");
      assertTrue(report.get("fixtureFinished").asBoolean(), "fixtureFinished must be true");
      assertNotNull(report.get("closeObserved"), "closeObserved must not be null");
      assertEquals(1000, report.get("closeObserved").get("code").asInt());

      int sentBytes = report.get("sentBytes").asInt();
      String sentSha = report.get("sentSha256").asText();
      assertEquals(sentBytes, body.length);
      assertEquals(sentSha, sha256Hex(body));

      if ("204k".equals(targetSize)) {
        assertTrue(
            body.length >= 203_000 && body.length <= 205_000,
            "204k target must be within [203000, 205000], got " + body.length);
      } else {
        assertTrue(
            body.length >= 306_000 && body.length <= 309_000,
            "307k target must be within [306000, 309000], got " + body.length);
        assertTrue(body.length > 262_144, "307k must exceed 256 KiB credit window");
      }

      JsonNode timing = report.get("timing");
      assertNotNull(timing, "timing must be reported");
      assertTrue(timing.get("firstBodyMs").isNumber(), "firstBodyMs must be a number");
      assertTrue(timing.get("lastBodyMs").isNumber(), "lastBodyMs must be a number");
      assertTrue(timing.get("endMs").isNumber(), "endMs must be a number");
      assertTrue(timing.get("closeMs").isNumber(), "closeMs must be a number");
      long firstBodyMs = timing.get("firstBodyMs").asLong();
      long lastBodyMs = timing.get("lastBodyMs").asLong();
      long endMs = timing.get("endMs").asLong();
      long closeMs = timing.get("closeMs").asLong();
      assertTrue(firstBodyMs <= lastBodyMs, "firstBodyMs must precede or equal lastBodyMs");
      assertTrue(lastBodyMs <= endMs, "lastBodyMs must precede or equal endMs");
      assertTrue(endMs <= closeMs, "endMs must precede or equal closeMs");
    } finally {
      shutdown(browser);
      NodeExecution startedNode = nodeRef.get();
      if (startedNode != null) {
        startedNode.close();
      }
    }
  }

  private static String resolveNodeBinary() {
    String envNode = System.getenv("NODE_BIN");
    if (envNode != null && !envNode.isBlank()) {
      return envNode;
    }
    String propNode = System.getProperty("node.bin");
    if (propNode != null && !propNode.isBlank()) {
      return propNode;
    }
    return "node";
  }

  private static Path repoRoot() {
    // Surefire 不提供 maven.basedir；user.dir 是 web 模块目录。尾部 "." 会被 toAbsolutePath 保留，
    // 直接 getParent 会停在 web，因此先 normalize。
    return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().getParent();
  }

  private static NodeExecution startNodeFixture(
      String streamUrl, String requestPath, String targetSize) throws IOException {
    Path repoRoot = repoRoot();
    Path fixture =
        repoRoot.resolve(
            "web/src/test/resources/fun/fengwk/kkstudio/web/environment/ide/ide-stream-node-fixture.mjs");
    ProcessBuilder pb =
        new ProcessBuilder(
            resolveNodeBinary(),
            fixture.toString(),
            repoRoot.toString(),
            streamUrl,
            requestPath,
            targetSize);
    pb.directory(repoRoot.toFile());
    Process process = pb.start();
    CompletableFuture<String> stdout =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException e) {
                throw new CompletionException(e);
              }
            });
    CompletableFuture<String> stderr =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException e) {
                throw new CompletionException(e);
              }
            });
    return new NodeExecution(process, stdout, stderr);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private record NodeExecution(
      Process process, CompletableFuture<String> stdout, CompletableFuture<String> stderr)
      implements AutoCloseable {

    JsonNode awaitReport(ObjectMapper mapper) throws Exception {
      boolean exited = process.waitFor(30, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
      }
      String err = stderr.get(5, TimeUnit.SECONDS);
      String out = stdout.get(5, TimeUnit.SECONDS);
      System.out.println("[node-fixture stderr]\n" + err);
      System.out.println("[node-fixture report]\n" + out);
      assertTrue(exited, "Node fixture must exit within timeout. Stderr: " + err);
      assertEquals(0, process.exitValue(), "Node fixture must exit cleanly. Stderr: " + err);
      assertTrue(!out.isBlank(), "Node fixture stdout must not be blank. Stderr: " + err);
      return mapper.readTree(out);
    }

    @Override
    public void close() throws InterruptedException {
      if (!process.isAlive()) {
        return;
      }
      process.destroyForcibly();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("node fixture did not exit after forced termination");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw error;
      }
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
          while (!window.canSendFrame(size)) {
            drainAcks();
          }
          window.onSendFrame(size);
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
  private String openSession(
      DaemonConnectionIdentity identity, String viewId, String workspacePath) {
    sessions.viewOpened(identity, viewId, workspacePath);
    return sessions.open(identity, viewId, workspacePath);
  }
}
