package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerContainer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeHeader;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.ide.stream.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.JdbcClusterNodeRegistry;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跨节点浏览器数据面的真实两节点一跳集成：浏览器落在非 owner 节点 A 的 IDE 网关路径，A 用内部 HMAC 单跳转发给 READY owner B。
 *
 * <p>B 是真实应用上下文（PostgreSQL 控制面、真实内部 REST/WS 端点、真实数据面）；A 是独立内嵌 Tomcat，只承载 IDE 网关入口（网关过滤器 + 数据面 + 浏览器
 * WS 网关）与公开反向 stream 端点，经真实 HTTP/WS 一跳连到 B。覆盖：20 MiB HTTP 与 WS echo 经 A→B 完整传输、 bootstrap/cookie 在
 * owner 消费并复用同一 cookie、两面板并存、内部 HMAC 无效、owner 切换/租约失效 fail-closed、peer 断开不自动重放。
 */
class IdeCrossNodeBrowserDataPlaneIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("44444444-4444-4444-4444-444444444444");

  private static final String SECRET = "cross-node-browser-secret";

  private static final int BODY_BYTES = 20 * 1024 * 1024;

  private static Tomcat forwarderNode;

  private static int forwarderPort;

  @DynamicPropertySource
  static void clusterProperties(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.cluster.secret", () -> SECRET);
  }

  @LocalServerPort private int ownerPort;

  @MockitoBean private IdeCommandTransport endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  @Autowired private IdeBootstrapTicketService bootstrapTickets;

  @Autowired private IdeGenerationCloseListener generationCloseListener;

  private final List<DaemonConnectionIdentity> caseGenerations = new ArrayList<>();

  @Autowired private JdbcClusterNodeRegistry nodeRegistry;

  @AfterAll
  static void stopForwarder() throws Exception {
    if (forwarderNode != null) {
      forwarderNode.stop();
      forwarderNode.destroy();
      forwarderNode = null;
    }
  }

  /** 20 MiB 响应体经「浏览器 → A 的 IDE 网关路径 → B 的内部数据面 → Daemon 反向连接」完整回到浏览器。 */
  @Test
  void forwardsTwentyMiBHttpThroughNonOwnerOrigin() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    NodePeer node = new NodePeer();
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              opens.incrementAndGet();
              node.dial(command.streamUrl());
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
                      .url(ideOriginUrl(forwarderPort, "/workspace/cross-node.txt"))
                      .header("Cookie", cookieHeader(cookie))
                      .header("Origin", ideOriginUrl(forwarderPort, ""))
                      .build())
              .execute();
      byte[] body = response.body().bytes();
      assertEquals(200, response.code());
      assertEquals(BODY_BYTES, body.length);
      assertEquals(expectedByte(BODY_BYTES - 1), body[BODY_BYTES - 1]);
      assertEquals(expectedByte(4242), body[4242]);
      assertNull(response.header("Proxy-Authenticate"), "hop-by-hop header must be stripped");
      assertTrue(node.completed.await(20, TimeUnit.SECONDS), "daemon peer must finish");
      assertEquals(1, opens.get(), "exactly one OPEN_STREAM, no replay");
      assertControlOnlyDatabase();
    } finally {
      shutdown(browser);
      node.awaitStopped();
    }
  }

  /** bootstrap 与非 owner Origin 的 cookie 转发：票据在 owner 消费，Set-Cookie 与跳转原样回到浏览器。 */
  @Test
  void forwardsBootstrapAndReusesGenerationCookie() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));

    String firstView = UUID.randomUUID().toString();
    String secondView = UUID.randomUUID().toString();
    sessions.viewOpened(identity, firstView, "/managed/one");
    sessions.viewOpened(identity, secondView, "/managed/two");
    String firstTicket = bootstrapTickets.issue(identity, firstView, "/managed/one");
    String secondTicket = bootstrapTickets.issue(identity, secondView, "/managed/two");

    OkHttpClient browser = noRedirectClient();
    try {
      Response first = bootstrap(browser, firstTicket);
      assertEquals(303, first.code());
      String firstCookie = sessionCookieOf(first);
      assertNotNull(firstCookie, "owner must set the host-only IDE session cookie");
      assertEquals(
          "/environment-ide/" + ENVIRONMENT + "/?workspace=%2Fmanaged%2Fone",
          first.header("Location"));

      Response second = bootstrap(browser, secondTicket);
      assertEquals(303, second.code());
      assertEquals(firstCookie, sessionCookieOf(second), "same generation must reuse one cookie");
      assertEquals(
          "/environment-ide/" + ENVIRONMENT + "/?workspace=%2Fmanaged%2Ftwo",
          second.header("Location"));

      // 两面板并存：同一 cookie 对两个受管 workspace 都已授权。
      assertEquals(1, sessions.size());
      assertTrue(sessions.lookup(firstCookie).orElseThrow().authorizesWorkspace("/managed/one"));
      assertTrue(sessions.lookup(firstCookie).orElseThrow().authorizesWorkspace("/managed/two"));
    } finally {
      shutdown(browser);
    }
  }

  /** 浏览器 WS 经 A 的原生分段桥接到 B，再由 B 配对 Daemon 反向连接完成 echo。 */
  @Test
  void forwardsBrowserWebSocketThroughNonOwnerOrigin() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    WsNodeDaemon node = new WsNodeDaemon(true);
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              node.dial(command.streamUrl());
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");

    OkHttpClient browser = localhostClient();
    try {
      BlockingQueue<String> echoed = new LinkedBlockingQueue<>();
      CountDownLatch opened = new CountDownLatch(1);
      WebSocket socket =
          browser.newWebSocket(
              new Request.Builder()
                  .url(ideWsUrl(forwarderPort, "/ws/echo"))
                  .header("Cookie", cookieHeader(cookie))
                  .header("Origin", ideOriginUrl(forwarderPort, ""))
                  .build(),
              new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                  opened.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                  echoed.add(text);
                }
              });
      assertTrue(opened.await(20, TimeUnit.SECONDS), "browser websocket must be established");
      String payload = "cross-node-ide-世界";
      assertTrue(socket.send(payload));
      assertTrue(node.sawMessage.await(20, TimeUnit.SECONDS), "daemon must see a WS segment");
      assertEquals(payload, echoed.poll(20, TimeUnit.SECONDS));
      socket.close(1000, "done");
    } finally {
      shutdown(browser);
      node.awaitStopped();
    }
  }

  /**
   * 20 MiB 浏览器二进制数据经 A→B 单跳分段透传，Daemon 按真实 credit 逐段 ACK 消费。
   *
   * <p>浏览器侧用 Jakarta 原生 {@code BasicRemote#sendBinary} 发出**一条** 20 MiB 消息（按 64 KiB 分段），而不是
   * OkHttp：OkHttp 的单条发送队列上限为 16 MiB，无法表达真实浏览器/原生 peer 的一条大消息。Daemon 必须收到一条消息（仅末段 FIN）的完整 20 MiB。
   */
  @Test
  void uploadsTwentyMiBSingleBrowserBinaryThroughNonOwnerOrigin() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    WsNodeDaemon node = new WsNodeDaemon(false);
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              assertNoCredentialHeaders(command.headers());
              opens.incrementAndGet();
              node.dial(command.streamUrl());
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    byte[] payload = new byte[BODY_BYTES];
    new Random(31).nextBytes(payload);

    try (NativeBrowserSocket browser = new NativeBrowserSocket()) {
      browser.connect(cookie, "/ws/upload");
      assertTrue(browser.awaitOpen(), "browser websocket must be established");
      Thread sender =
          Thread.ofVirtual()
              .name("native-browser-sender")
              .start(() -> browser.sendBinaryMessage(payload));
      assertTrue(
          awaitBinaryBytes(node, BODY_BYTES, browser),
          "daemon must consume the whole 20 MiB message: " + node.describe() + browser.describe());
      sender.join(TimeUnit.SECONDS.toMillis(30));
      assertFalse(sender.isAlive(), "native sender must finish: " + browser.describe());
      assertEquals(BODY_BYTES, node.binaryBytes.get(), "total forwarded bytes must match");
      assertArrayEquals(
          sha256(payload),
          node.binaryDigest(),
          "streaming digest must equal payload SHA-256 (no reorder, duplicate or drop)");
      List<Boolean> fins;
      synchronized (node.binaryFins) {
        fins = new ArrayList<>(node.binaryFins);
      }
      assertTrue(fins.size() > 1, "20 MiB must arrive as many segments: " + node.describe());
      assertEquals(
          1,
          fins.stream().filter(Boolean::booleanValue).count(),
          "exactly one FIN for the single message");
      assertTrue(fins.get(fins.size() - 1), "the single FIN must be on the last segment");
      assertEquals(1, opens.get(), "exactly one OPEN_STREAM, no replay");
    } finally {
      node.awaitStopped();
    }
  }

  /** 20 MiB Daemon → 浏览器反向大消息：Daemon 一次推送按 wire 分段，网关按 credit 全量交付给浏览器。 */
  @Test
  void streamsTwentyMiBDaemonBinaryToBrowserThroughNonOwnerOrigin() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    WsNodeDaemon node = new WsNodeDaemon(false);
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              node.dial(command.streamUrl());
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    byte[] payload = new byte[BODY_BYTES];
    new Random(29).nextBytes(payload);

    OkHttpClient browser = localhostClient();
    try (BrowserSocket socket = browserSocket(browser, cookie, "/ws/download")) {
      assertTrue(socket.awaitOpen(), "browser websocket must be established");
      assertTrue(node.awaitReady(), "daemon reverse connection must be ready");
      Thread push = Thread.ofVirtual().start(() -> pushMessage(node, payload));
      byte[] received = socket.binaryMessages.poll(120, TimeUnit.SECONDS);
      assertNotNull(received, "browser must receive the whole daemon message");
      assertArrayEquals(payload, received, "reverse direction must preserve every byte");
      push.join(30_000);
    } finally {
      shutdown(browser);
      node.awaitStopped();
    }
  }

  /** Daemon 文本分段可在码点中间切分：网关跨分段流式解码，浏览器收到完整字符串，且只有消息末段带 FIN。 */
  @Test
  void streamsUtf8TextSplittingCodepointsThroughNonOwnerOrigin() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    WsNodeDaemon node = new WsNodeDaemon(false);
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              node.dial(command.streamUrl());
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    StringBuilder builder = new StringBuilder();
    while (builder.length() < 400_000) {
      builder.append("世界");
    }
    String text = builder.toString();
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

    OkHttpClient browser = localhostClient();
    try (BrowserSocket socket = browserSocket(browser, cookie, "/ws/text")) {
      assertTrue(socket.awaitOpen(), "browser websocket must be established");
      assertTrue(node.awaitReady(), "daemon reverse connection must be ready");
      Thread push = Thread.ofVirtual().start(() -> pushMessage(node, bytes, true));
      assertEquals(text, socket.textMessages.poll(120, TimeUnit.SECONDS));
      push.join(30_000);
    } finally {
      shutdown(browser);
      node.awaitStopped();
    }
  }

  /** 代际结束（另一个 Daemon 实例接管）必须立即主动关闭已建立的浏览器 WS 与 Daemon 反向连接。 */
  @Test
  void generationCloseActivelyClosesBrowserWebSocketThroughNonOwnerOrigin() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    WsNodeDaemon node = new WsNodeDaemon(true);
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              node.dial(command.streamUrl());
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");

    OkHttpClient browser = localhostClient();
    try (BrowserSocket socket = browserSocket(browser, cookie, "/ws/close")) {
      assertTrue(socket.awaitOpen(), "browser websocket must be established");
      assertTrue(node.awaitReady(), "daemon reverse connection must be ready");
      // 真实代际结束钩子（B 的组合根装配）：pairings.closeGeneration + 票据 revoke + 会话代际清除。
      generationCloseListener.onGenerationClosed(identity);
      assertTrue(
          socket.closed.await(20, TimeUnit.SECONDS),
          "browser websocket must be actively closed when its generation ends");
      assertEquals(
          1011, socket.closeCode, "owner hop close must use the fixed Jakarta-legal safe status");
      assertTrue(node.closed.await(20, TimeUnit.SECONDS), "daemon reverse connection must close");
    } finally {
      shutdown(browser);
      node.awaitStopped();
    }
  }

  /**
   * 浏览器侧原生 Jakarta WS 客户端：完全掌控握手头（Host/Origin/Cookie），并用 {@code BasicRemote#sendBinary}
   * 的原生分段发出**一条**大消息。
   *
   * <p>它不受 OkHttp 单条发送队列 16 MiB 上限约束，因此能表达真实浏览器/原生 peer 的一条 20 MiB 消息。
   */
  private final class NativeBrowserSocket implements AutoCloseable {

    private final CountDownLatch opened = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile Session session;
    private volatile int closeCode = -1;

    void connect(String cookie, String path) throws Exception {
      ClientEndpointConfig config =
          ClientEndpointConfig.Builder.create()
              .configurator(
                  new ClientEndpointConfig.Configurator() {
                    @Override
                    public void beforeRequest(Map<String, List<String>> headers) {
                      // TCP 目标是 localhost，Studio 同源路径下的 Host/Origin 与浏览器完全一致。
                      headers.put("Host", List.of("localhost:" + forwarderPort));
                      headers.put("Cookie", List.of(cookieHeader(cookie)));
                      headers.put("Origin", List.of("http://localhost:" + forwarderPort));
                    }
                  })
              .build();
      WebSocketContainer container = ContainerProvider.getWebSocketContainer();
      session =
          container.connectToServer(
              new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig endpointConfig) {
                  opened.countDown();
                }

                @Override
                public void onClose(Session session, CloseReason closeReason) {
                  closeCode = closeReason.getCloseCode().getCode();
                  closed.countDown();
                }

                @Override
                public void onError(Session session, Throwable error) {
                  failure.compareAndSet(null, error);
                  closed.countDown();
                }
              },
              config,
              URI.create(ideWsUrl(forwarderPort, path)));
    }

    boolean awaitOpen() throws InterruptedException {
      return opened.await(20, TimeUnit.SECONDS) || session != null;
    }

    /**
     * 发送**一条**完整消息：按 64 KiB 原生分段，只有最后一段带 FIN。
     *
     * <p>发送线程上的任何失败都必须显式落到本句柄的 failure/closed 上，绝不静默丢弃，否则只能靠 deadline 超时才发现。
     */
    void sendBinaryMessage(byte[] payload) {
      try {
        Session current = session;
        if (current == null) {
          throw new IllegalStateException("native browser websocket is not connected");
        }
        RemoteEndpoint.Basic remote = current.getBasicRemote();
        int offset = 0;
        while (offset < payload.length) {
          int size = Math.min(IdeBrowserStreamSession.SEGMENT_BYTES, payload.length - offset);
          offset += size;
          remote.sendBinary(
              ByteBuffer.wrap(payload, offset - size, size), offset == payload.length);
        }
      } catch (IOException | RuntimeException error) {
        failure.compareAndSet(null, error);
        closed.countDown();
      }
    }

    Throwable failure() {
      return failure.get();
    }

    boolean isClosed() {
      return closed.getCount() == 0;
    }

    String describe() {
      Throwable error = failure.get();
      return "browser[close="
          + closeCode
          + " closed="
          + (closed.getCount() == 0)
          + " failure="
          + (error == null ? "null" : error.toString())
          + "]";
    }

    @Override
    public void close() {
      Session current = session;
      if (current != null) {
        try {
          current.close();
        } catch (IOException ignored) {
          // 已断开。
        }
      }
    }
  }

  /** 浏览器侧 WS 句柄：收集完整消息（OkHttp 自动重组 partial 帧）与关闭事件。 */
  private final class BrowserSocket implements AutoCloseable {

    private final WebSocket socket;
    private final CountDownLatch opened = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final BlockingQueue<byte[]> binaryMessages = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> textMessages = new LinkedBlockingQueue<>();
    private volatile int closeCode = -1;
    private volatile String closeReason;
    private volatile Throwable failure;

    private BrowserSocket(OkHttpClient browser, String cookie, String path) {
      this.socket =
          browser.newWebSocket(
              new Request.Builder()
                  .url(ideWsUrl(forwarderPort, path))
                  .header("Cookie", cookieHeader(cookie))
                  .header("Origin", ideOriginUrl(forwarderPort, ""))
                  .build(),
              new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                  BrowserSocket.this.opened.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                  BrowserSocket.this.textMessages.add(text);
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                  BrowserSocket.this.binaryMessages.add(bytes.toByteArray());
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                  record(code, reason);
                  webSocket.close(1000, null);
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                  record(code, reason);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                  failure = error;
                  record(-1);
                }
              });
    }

    boolean awaitOpen() throws InterruptedException {
      return opened.await(20, TimeUnit.SECONDS);
    }

    String describe() {
      return "closeCode=" + closeCode + " reason=" + closeReason + " failure=" + failure;
    }

    boolean sendBinary(byte[] payload) {
      return socket.send(ByteString.of(payload));
    }

    private void record(int code, String reason) {
      closeCode = code;
      closeReason = reason;
      closed.countDown();
    }

    private void record(int code) {
      record(code, null);
    }

    @Override
    public void close() {
      socket.cancel();
    }
  }

  private BrowserSocket browserSocket(OkHttpClient browser, String cookie, String path) {
    return new BrowserSocket(browser, cookie, path);
  }

  /** 等待 Daemon 收满预期字节；任一 WS 提前关闭立即返回，避免必须等满 deadline 才知道失败。 */
  private static boolean awaitBinaryBytes(
      WsNodeDaemon node, long expected, NativeBrowserSocket browser) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    while (System.nanoTime() < deadline) {
      if (node.binaryBytes.get() >= expected) {
        return true;
      }
      if (node.closed.getCount() == 0 || browser.isClosed() || browser.failure() != null) {
        return false;
      }
      Thread.sleep(20);
    }
    return false;
  }

  private static boolean awaitBinaryBytes(WsNodeDaemon node, long expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    while (System.nanoTime() < deadline) {
      if (node.binaryBytes.get() >= expected) {
        return true;
      }
      Thread.sleep(20);
    }
    return false;
  }

  private static void pushMessage(WsNodeDaemon node, byte[] payload) {
    pushMessage(node, payload, false);
  }

  private static void pushMessage(WsNodeDaemon node, byte[] payload, boolean text) {
    try {
      node.pushMessage(text, payload);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  /** Node 控制面（OPEN_STREAM）绝不携带浏览器凭据、代理链或内部路由头。 */
  private static void assertNoCredentialHeaders(List<IdeHeader> headers) {
    for (IdeHeader header : headers) {
      String name = header.name().toLowerCase(Locale.ROOT);
      assertFalse(
          name.equals("cookie")
              || name.equals("authorization")
              || name.equals("proxy-authorization")
              || name.equals("origin")
              || name.equals("referer")
              || name.equals("forwarded")
              || name.equals("sec-websocket-key")
              || name.startsWith("x-kk-cluster-")
              || name.startsWith("x-forwarded-"),
          "credential/proxy header must never reach the daemon control plane: " + name);
    }
  }

  /** 内部 HMAC 无效：owner 在读取请求体之前以 401 拒绝，且不触发任何 Daemon 副作用。 */
  @Test
  void rejectsInvalidInternalSignatureBeforeDataPlane() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              opens.incrementAndGet();
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(
                  new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1"), UUID.randomUUID(), reply);
            });

    String path = IdeInternalPaths.dataHttpPath(ENVIRONMENT, "/workspace/secret.txt");
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + ownerPort + path))
                    .header("X-KK-Cluster-Target", UUID.randomUUID().toString())
                    .header("X-KK-Cluster-Timestamp", "1")
                    .header("X-KK-Cluster-Nonce", "n")
                    .header("X-KK-Cluster-Signature", "forged")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(401, response.statusCode());
    assertEquals(0, opens.get(), "unauthenticated request must not reach the data plane");
  }

  /** owner 已切换：陈旧路由的内部请求返回未受理（409），绝不递归转发或重放。 */
  @Test
  void rejectsStaleOwnerSwitchWithoutReplay() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(UUID.randomUUID());
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              opens.incrementAndGet();
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(
                  new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1"), UUID.randomUUID(), reply);
            });
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");

    OkHttpClient browser = localhostClient();
    try {
      Response response =
          browser
              .newCall(
                  new Request.Builder()
                      .url(ideOriginUrl(forwarderPort, "/workspace/other.txt"))
                      .header("Cookie", cookieHeader(cookie))
                      .header("Origin", ideOriginUrl(forwarderPort, ""))
                      .build())
              .execute();
      assertEquals(409, response.code());
      assertEquals(0, opens.get(), "stale owner must not reach the data plane");
    } finally {
      shutdown(browser);
    }
  }

  /** 代际已被另一个 Daemon 实例接管：owner 拒绝该 cookie，且不触发任何 Daemon 副作用。 */
  @Test
  void rejectsReplacedGeneration() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity stale = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    DaemonConnectionIdentity current = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-2");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(current));
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              opens.incrementAndGet();
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(current, UUID.randomUUID(), reply);
            });
    String cookie = openSession(stale, UUID.randomUUID().toString(), "/home/dev/project");

    OkHttpClient browser = localhostClient();
    try {
      Response response =
          browser
              .newCall(
                  new Request.Builder()
                      .url(ideOriginUrl(forwarderPort, "/workspace/stale.txt"))
                      .header("Cookie", cookieHeader(cookie))
                      .header("Origin", ideOriginUrl(forwarderPort, ""))
                      .build())
              .execute();
      assertEquals(409, response.code());
      assertEquals(0, opens.get(), "stale generation must not reach the data plane");
    } finally {
      shutdown(browser);
    }
  }

  /** Daemon peer 中途断开：不自动重放，OPEN_STREAM 只发生一次且浏览器侧以失败收尾。 */
  @Test
  void doesNotReplayWhenDaemonPeerDisconnects() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1");
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    NodePeer node = new NodePeer();
    node.disconnectAfterBytes = 1024 * 1024;
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              opens.incrementAndGet();
              node.dial(command.streamUrl());
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");

    OkHttpClient browser = localhostClient();
    try {
      boolean failed;
      try (Response response =
          browser
              .newCall(
                  new Request.Builder()
                      .url(ideOriginUrl(forwarderPort, "/workspace/broken.txt"))
                      .header("Cookie", cookieHeader(cookie))
                      .header("Origin", ideOriginUrl(forwarderPort, ""))
                      .build())
              .execute()) {
        byte[] body = response.body().bytes();
        failed = body.length < BODY_BYTES;
      } catch (IOException error) {
        failed = true;
      }
      assertTrue(failed, "browser must observe a truncated/failed response");
      assertEquals(1, opens.get(), "a failed exchange must never be replayed");
    } finally {
      shutdown(browser);
      node.awaitStopped();
    }
  }

  private static byte expectedByte(int index) {
    return (byte) (index * 31 + 7);
  }

  private Response bootstrap(OkHttpClient browser, String ticket) throws IOException {
    return browser
        .newCall(
            new Request.Builder()
                .url(ideOriginUrl(forwarderPort, "/__ide/bootstrap?token=" + ticket))
                .header("Origin", ideOriginUrl(forwarderPort, ""))
                .build())
        .execute();
  }

  private String sessionCookieOf(Response response) {
    String name = gatewayProperties.requireSessionCookieName();
    for (String header : response.headers("Set-Cookie")) {
      for (String part : header.split(";")) {
        String trimmed = part.trim();
        if (trimmed.startsWith(name + "=")) {
          return trimmed.substring(name.length() + 1);
        }
      }
    }
    return null;
  }

  private String cookieHeader(String cookie) {
    return gatewayProperties.requireSessionCookieName() + "=" + cookie;
  }

  private String ideOriginUrl(int port, String path) {
    if (path == null || path.isEmpty()) {
      return "http://localhost:" + port;
    }
    String p = path.startsWith("/") ? path : "/" + path;
    return "http://localhost:" + port + "/environment-ide/" + ENVIRONMENT + p;
  }

  private String ideWsUrl(int port, String path) {
    String p = path.startsWith("/") ? path : "/" + path;
    return "ws://localhost:" + port + "/environment-ide/" + ENVIRONMENT + p;
  }

  /** 建立本 Environment 的 READY owner 行：owner 为给定节点实例，租约在未来。 */
  private void registerReadyOwner(UUID ownerNodeId) throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        Statement st = conn.createStatement()) {
      st.execute(
          "insert into environment (id, name, registration_token_hash) values ('"
              + ENVIRONMENT
              + "', 'cross-node-browser-env', repeat('a', 64))"
              + " on conflict (id) do nothing");
      st.execute(
          "insert into environment_connection"
              + " (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at,"
              + " lease_until)"
              + " values ('"
              + ENVIRONMENT
              + "', '"
              + ownerNodeId
              + "', '"
              + UUID.randomUUID()
              + "', 'READY', '{}'::jsonb, statement_timestamp(),"
              + " statement_timestamp() + interval '60 seconds')"
              + " on conflict (environment_id) do update set owner_node_id = excluded.owner_node_id,"
              + " lease_token = excluded.lease_token, status = excluded.status,"
              + " runtime_info = excluded.runtime_info, last_seen_at = excluded.last_seen_at,"
              + " lease_until = excluded.lease_until");
    }
  }

  /** PG 只承载 registry 控制数据。 */
  private void assertControlOnlyDatabase() throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        Statement st = conn.createStatement()) {
      try (var rs = st.executeQuery("select count(*) from environment_connection")) {
        assertTrue(rs.next());
        assertEquals(1L, rs.getLong(1));
      }
    }
  }

  /**
   * 启动非 owner 节点 A：独立内嵌 Tomcat，承载 IDE 网关入口（网关过滤器 + 数据面 + 浏览器 WS 网关）与公开反向 stream 端点。
   *
   * <p>A 自己不做 cookie/代际鉴权，全部单跳交给 owner B；这正是「非 owner 的 IDE 网关请求先单跳到 owner 再消费 bootstrap/cookie」。
   */
  private static synchronized void startForwarderNode(UUID ownerNodeId, int ownerPort)
      throws Exception {
    if (forwarderNode != null) {
      return;
    }
    int port = freePort();
    ClusterInternalAuthentication authentication =
        new HmacClusterInternalAuthentication(
            SECRET, UUID.randomUUID(), Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    IdeClusterClient client =
        new IdeClusterClient(
            HttpClient.newHttpClient(), JsonMapper.builder().build(), authentication);
    ClusterNodeRegistry remoteRegistry =
        new ClusterNodeRegistry() {
          @Override
          public Optional<ClusterOwnerRoute> route(EnvironmentId environmentId) {
            return Optional.of(
                ClusterOwnerRoute.remote(ownerNodeId, URI.create("http://localhost:" + ownerPort)));
          }

          @Override
          public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
            return Optional.empty();
          }
        };
    IdeGatewayProperties properties = new IdeGatewayProperties();
    IdeBrowserSessionRegistry registry =
        new IdeBrowserSessionRegistry(new SecureRandom(), Clock.systemUTC(), Duration.ofHours(1));
    IdeRemoteDataPlaneForwarder forwarder = new IdeRemoteDataPlaneForwarder(client, properties);
    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(
            unavailableTransport(),
            new InMemoryIdeStreamTicketService(),
            new IdeStreamPairingRegistry<>(),
            properties,
            registry,
            new InMemoryIdeBootstrapTicketService(),
            remoteRegistry,
            forwarder);
    IdeBrowserWebSocketGateway browserGateway =
        new IdeBrowserWebSocketGateway(
            unavailableTransport(),
            new InMemoryIdeStreamTicketService(),
            new IdeStreamPairingRegistry<>(),
            properties,
            registry,
            remoteRegistry,
            client,
            ideWebSocketClient(properties));
    IdeGatewayFilter filter = new IdeGatewayFilter(dataPlane, browserGateway);
    IdeReverseStreamWebSocketHandler reverseHandler =
        new IdeReverseStreamWebSocketHandler(
            new InMemoryIdeStreamTicketService(),
            new IdeStreamPairingRegistry<>(),
            unavailableTransport(),
            remoteRegistry,
            new IdeOwnerStreamForwarder(client, ideWebSocketClient(properties)));
    DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();

    Tomcat tomcat = new Tomcat();
    tomcat.setPort(port);
    tomcat.setBaseDir(System.getProperty("java.io.tmpdir"));
    tomcat.getConnector();
    Context context = tomcat.addContext("", new File(".").getAbsolutePath());
    context.addServletContainerInitializer(new WsSci(), null);
    Tomcat.addServlet(
        context,
        "ideGateway",
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest request, HttpServletResponse response)
              throws ServletException, IOException {
            filter.doFilter(
                request,
                response,
                (req, resp) ->
                    ((HttpServletResponse) resp).sendError(HttpServletResponse.SC_NOT_FOUND));
          }
        });
    Tomcat.addServlet(
        context,
        "ideReverseStream",
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest request, HttpServletResponse response)
              throws ServletException, IOException {
            handshakeHandler.setServletContext(request.getServletContext());
            handshakeHandler.start();
            new WebSocketHttpRequestHandler(reverseHandler, handshakeHandler)
                .handleRequest(request, response);
          }
        });
    context.addServletMappingDecoded("/environment-ide/*", "ideGateway");
    context.addServletMappingDecoded(IdeGatewayProperties.STREAM_PATH + "/*", "ideReverseStream");
    tomcat.start();
    ServerContainer serverContainer =
        (ServerContainer) context.getServletContext().getAttribute(ServerContainer.class.getName());
    forwarderNode = tomcat;
    forwarderPort = tomcat.getConnector().getLocalPort();
  }

  /** 与生产装配一致：把握手 I/O 超时对齐到 pairing-timeout 的 WS 客户端。 */
  private static StandardWebSocketClient ideWebSocketClient(IdeGatewayProperties properties) {
    StandardWebSocketClient client = new StandardWebSocketClient();
    client.setUserProperties(
        Map.of(
            IdeGatewayConfiguration.TOMCAT_HANDSHAKE_IO_TIMEOUT_MS,
            String.valueOf(properties.requirePairingTimeout().toMillis())));
    return client;
  }

  private static IdeCommandTransport unavailableTransport() {
    return new IdeCommandTransport() {
      @Override
      public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
        throw new IllegalStateException("non-owner node must not issue IDE commands");
      }

      @Override
      public void abandonIde(UUID invocationId) {}

      @Override
      public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
        return Optional.empty();
      }
    };
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /** 关闭重定向跟随：bootstrap 的 303 必须原样回到浏览器，而不是被测试客户端自行跟随。 */
  private static OkHttpClient noRedirectClient() {
    return new OkHttpClient.Builder()
        .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
        .followRedirects(false)
        .followSslRedirects(false)
        .build();
  }

  private static OkHttpClient localhostClient() {
    return new OkHttpClient.Builder()
        .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
        .build();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static byte[] sha256(byte[] payload) {
    return sha256().digest(payload);
  }

  private static void shutdown(OkHttpClient client) {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  /** 充当 Daemon 的 Node peer：把受控 streamUrl 重写到 A 的公网地址后按 credit 回 20 MiB。 */
  private final class NodePeer extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private final CountDownLatch completed = new CountDownLatch(1);
    private final Thread worker = new Thread(this::serve, "cross-node-browser-peer");

    int disconnectAfterBytes = Integer.MAX_VALUE;
    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    NodePeer() {
      this.worker.setDaemon(true);
    }

    void dial(String streamUrl) {
      URI original = URI.create(streamUrl);
      String ticket = original.getPath().substring(original.getPath().lastIndexOf('/') + 1);
      String target =
          "ws://localhost:"
              + forwarderPort
              + IdeGatewayProperties.STREAM_PATH
              + "/"
              + ticket
              + "?environment="
              + ENVIRONMENT;
      client = new OkHttpClient();
      client.newWebSocket(new Request.Builder().url(target).build(), this);
      worker.start();
    }

    private void serve() {
      try {
        byte[] frame = inbound.poll(30, TimeUnit.SECONDS);
        if (frame == null || frame[0] != 2) {
          return;
        }
        socket.send(
            "{\"type\":\"HTTP_RESPONSE\",\"status\":200,\"headers\":"
                + "[[\"content-type\",\"text/plain\"],[\"content-length\",\""
                + BODY_BYTES
                + "\"],[\"proxy-authenticate\",\"Basic realm=hop\"]]}");
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
            byte[] ack = inbound.poll(30, TimeUnit.SECONDS);
            if (ack == null || ack[0] != 3) {
              return;
            }
            window.onAck(unsignedInt(ack));
          }
          window.onSendFrame(size);
          socket.send(ByteString.of(codec.encodeHttpBody(payload)));
          sent += size;
          if (sent >= disconnectAfterBytes) {
            // 中途断开：模拟 Daemon 侧连接丢失。
            socket.cancel();
            return;
          }
        }
        socket.send(ByteString.of(codec.encodeHttpEnd()));
        completed.countDown();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }

    private static long unsignedInt(byte[] frame) {
      return ((long) (frame[1] & 0xFF) << 24)
          | ((long) (frame[2] & 0xFF) << 16)
          | ((long) (frame[3] & 0xFF) << 8)
          | (frame[4] & 0xFF);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      this.socket = webSocket;
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      inbound.add(bytes.toByteArray());
    }

    /** 先取消 socket 停止网络活动，再中断并有限等待 worker，避免每个 case 白等或残留线程影响下一个用例。 */
    void awaitStopped() throws InterruptedException {
      WebSocket current = socket;
      if (current != null) {
        current.cancel();
      }
      OkHttpClient currentClient = client;
      if (currentClient != null) {
        shutdown(currentClient);
      }
      worker.interrupt();
      worker.join(5_000);
    }
  }

  /** 充当 Daemon 的 WS peer：消费/回显浏览器分段，并可按 credit 主动推送大消息（真实 wire 分段 ≤64 KiB）。 */
  private final class WsNodeDaemon extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final IdeStreamCreditWindow sendCredit = new IdeStreamCreditWindow();
    private final Object creditLock = new Object();
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch sawMessage = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicLong binaryBytes = new AtomicLong();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicInteger closeCode = new AtomicInteger(-1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong textBytes = new AtomicLong();
    private final List<Boolean> binaryFins = Collections.synchronizedList(new ArrayList<>());
    private final List<Boolean> textFins = Collections.synchronizedList(new ArrayList<>());

    /** 流式 payload 摘要：逐段 update，绝不整包缓冲，用于实证字节内容（重排/重复会被发现）。 */
    private final Object digestLock = new Object();

    private final MessageDigest binaryDigest = sha256();
    private final MessageDigest textDigest = sha256();
    private final Thread worker = new Thread(this::serve, "cross-node-browser-ws-daemon");

    private final boolean echo;
    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    WsNodeDaemon(boolean echo) {
      this.echo = echo;
      worker.setDaemon(true);
    }

    void dial(String streamUrl) {
      URI original = URI.create(streamUrl);
      String ticket = original.getPath().substring(original.getPath().lastIndexOf('/') + 1);
      String target =
          "ws://localhost:"
              + forwarderPort
              + IdeGatewayProperties.STREAM_PATH
              + "/"
              + ticket
              + "?environment="
              + ENVIRONMENT;
      client = new OkHttpClient();
      client.newWebSocket(new Request.Builder().url(target).build(), this);
      worker.start();
    }

    /** 消费 gateway → daemon 的分段：统计、回 ACK（释放发送 credit），可选 echo。 */
    private void serve() {
      try {
        while (true) {
          byte[] frame = inbound.poll(30, TimeUnit.SECONDS);
          if (frame == null) {
            return;
          }
          if (frame.length < 2) {
            continue;
          }
          frames.incrementAndGet();
          int flag = frame[0] & 0xFF;
          WebSocket current = socket;
          if (current == null) {
            return;
          }
          if (flag == 4 || flag == 5) {
            boolean last = frame[1] == 1;
            byte[] payload = Arrays.copyOfRange(frame, 2, frame.length);
            synchronized (digestLock) {
              (flag == 5 ? binaryDigest : textDigest).update(payload);
            }
            if (flag == 5) {
              binaryBytes.addAndGet(payload.length);
              binaryFins.add(last);
            } else {
              textBytes.addAndGet(payload.length);
              textFins.add(last);
            }
            sawMessage.countDown();
            current.send(
                ByteString.of(codec.encodeAck(IdeStreamCreditWindow.frameCost(payload.length))));
            if (echo) {
              current.send(ByteString.of(frame));
            }
          } else if (flag == 3) {
            synchronized (creditLock) {
              sendCredit.onAck(unsignedInt(frame));
              creditLock.notifyAll();
            }
          }
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }

    /** 按 credit 推送一条完整消息：分段 ≤64 KiB，只有最后一段带 FIN；阻塞直到全部发出。 */
    void pushMessage(boolean text, byte[] payload) throws InterruptedException {
      if (!ready.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("daemon reverse connection is not ready");
      }
      int offset = 0;
      while (offset < payload.length) {
        int size = Math.min(DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES, payload.length - offset);
        boolean last = offset + size == payload.length;
        awaitCredit(size);
        WebSocket current = socket;
        if (current == null) {
          throw new IllegalStateException("daemon reverse socket is gone");
        }
        current.send(
            ByteString.of(
                codec.encodeWsSegment(
                    text, last, Arrays.copyOfRange(payload, offset, offset + size))));
        offset += size;
      }
    }

    /**
     * 等待 credit：ACK **只由 {@link #serve()} 线程消费**（单线程记账），这里只等待额度释放，并在关闭/失败/超时时 fail-fast。
     *
     * <p>非法 ACK（负数或超过未确认发送量）仍由 credit 窗口自身拒绝，绝不被静默吞掉。
     */
    private void awaitCredit(int size) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      synchronized (creditLock) {
        while (!sendCredit.canSendFrame(size)) {
          if (failure.get() != null || closed.getCount() == 0) {
            throw new IllegalStateException(
                "daemon reverse connection closed while waiting credit");
          }
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0L) {
            throw new IllegalStateException("daemon credit did not clear in time");
          }
          TimeUnit.NANOSECONDS.timedWait(creditLock, remaining);
        }
        sendCredit.onSendFrame(size);
      }
    }

    /** 摘要快照：clone 后 digest，不打断 serve 线程的流式 update（SHA-256 支持 clone）。 */
    byte[] binaryDigest() {
      synchronized (digestLock) {
        try {
          return ((MessageDigest) binaryDigest.clone()).digest();
        } catch (CloneNotSupportedException error) {
          throw new IllegalStateException(error);
        }
      }
    }

    boolean awaitReady() throws InterruptedException {
      return ready.await(20, TimeUnit.SECONDS);
    }

    /** 先取消 socket 停止网络活动，再中断并有限等待 worker，避免每个 case 白等或残留线程影响下一个用例。 */
    void awaitStopped() throws InterruptedException {
      WebSocket current = socket;
      if (current != null) {
        current.cancel();
      }
      OkHttpClient currentClient = client;
      if (currentClient != null) {
        shutdown(currentClient);
      }
      worker.interrupt();
      worker.join(5_000);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      this.socket = webSocket;
      webSocket.send("{\"type\":\"WS_READY\"}");
      ready.countDown();
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      inbound.add(bytes.toByteArray());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      closeCode.set(code);
      webSocket.close(1000, null);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      closeCode.set(code);
      closed.countDown();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      failure.compareAndSet(null, error);
      closed.countDown();
    }

    /** Daemon 侧状态：用于在超时前直接定位失败阶段，而不是等到 deadline 才失败。 */
    String describe() {
      Throwable error = failure.get();
      return "daemon[frames="
          + frames.get()
          + " bytes="
          + binaryBytes.get()
          + " fins="
          + binaryFins.size()
          + " close="
          + closeCode.get()
          + " failure="
          + (error == null ? "null" : error.toString())
          + "]";
    }

    private static long unsignedInt(byte[] frame) {
      return ((long) (frame[1] & 0xFF) << 24)
          | ((long) (frame[2] & 0xFF) << 16)
          | ((long) (frame[3] & 0xFF) << 8)
          | (frame[4] & 0xFF);
    }
  }

  /** bootstrap 消费的真实前置：先由真实 OPEN_VIEW 授权视图，再消费一次性票据换 cookie。 */
  private String openSession(
      DaemonConnectionIdentity identity, String viewId, String workspacePath) {
    caseGenerations.add(identity);
    sessions.viewOpened(identity, viewId, workspacePath);
    return sessions.open(identity, viewId, workspacePath);
  }

  /**
   * 每个用例结束都 drain 本用例 owner 侧会话：关闭反向流配对、吊销 stream/bootstrap 票据并清代际视图，绝不把残留状态留给下一个用例 （schema 重置会
   * drop 表，残留会话的后续查询只会在日志里制造假 WARN）。
   */
  @AfterEach
  void drainCaseSessions() {
    for (DaemonConnectionIdentity generation : caseGenerations) {
      generationCloseListener.onGenerationClosed(generation);
    }
    caseGenerations.clear();
  }
}
