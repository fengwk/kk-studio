package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.JdbcClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;
import fun.fengwk.kkstudio.platform.environment.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨节点浏览器数据面的真实两节点一跳集成：浏览器落在非 owner 节点 A 的 IDE Origin，A 用内部 HMAC 单跳转发给 READY owner B。
 *
 * <p>B 是真实应用上下文（PostgreSQL 控制面、真实内部 REST/WS 端点、真实数据面）；A 是独立内嵌 Tomcat，只承载 IDE Origin 入口（host guard +
 * 数据面 + 浏览器 WS 网关）与公开反向 stream 端点，经真实 HTTP/WS 一跳连到 B。覆盖：20 MiB HTTP 与 WS echo 经 A→B 完整传输、
 * bootstrap/cookie 在 owner 消费并复用同一 cookie、两面板并存、内部 HMAC 无效、owner 切换/租约失效 fail-closed、peer 断开不自动重放。
 */
class IdeCrossNodeBrowserDataPlaneIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("44444444-4444-4444-4444-444444444444");

  private static final String SECRET = "cross-node-browser-secret";

  private static final int BODY_BYTES = 20 * 1024 * 1024;

  private static final String ORIGIN_TEMPLATE = "https://{environmentId}.ide.test";

  private static Tomcat forwarderNode;

  private static int forwarderPort;

  @DynamicPropertySource
  static void clusterProperties(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.cluster.secret", () -> SECRET);
    registry.add(
        "kk-studio.harness.ide-gateway.stream-url-template",
        () -> "wss://studio-lb" + IdeGatewayProperties.STREAM_PATH);
    registry.add("kk-studio.harness.ide.origin-template", () -> ORIGIN_TEMPLATE);
  }

  @LocalServerPort private int ownerPort;

  @MockitoBean private EnvironmentDaemonServer endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  @Autowired private IdeBootstrapTicketService bootstrapTickets;

  @Autowired private JdbcClusterNodeRegistry nodeRegistry;

  @AfterAll
  static void stopForwarder() throws Exception {
    if (forwarderNode != null) {
      forwarderNode.stop();
      forwarderNode.destroy();
      forwarderNode = null;
    }
  }

  /** 20 MiB 响应体经「浏览器 → A 的 IDE Origin → B 的内部数据面 → Daemon 反向连接」完整回到浏览器。 */
  @Test
  void forwardsTwentyMiBHttpThroughNonOwnerOrigin() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
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
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));

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
      assertEquals("/?workspace=%2Fmanaged%2Fone", first.header("Location"));

      Response second = bootstrap(browser, secondTicket);
      assertEquals(303, second.code());
      assertEquals(firstCookie, sessionCookieOf(second), "same generation must reuse one cookie");
      assertEquals("/?workspace=%2Fmanaged%2Ftwo", second.header("Location"));

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
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    WsEchoDaemon node = new WsEchoDaemon();
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
                  new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID()),
                  UUID.randomUUID(),
                  reply);
            });

    String path = ClusterInternalPaths.dataHttpPath(ENVIRONMENT, "/workspace/secret.txt");
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
                  new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID()),
                  UUID.randomUUID(),
                  reply);
            });
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
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

  /** 代际（租约）失效：owner 拒绝该 cookie，且不触发任何 Daemon 副作用。 */
  @Test
  void rejectsStaleLeaseGeneration() throws Exception {
    startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner(nodeRegistry.localNodeId());
    IdeSessionIdentity stale = new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    IdeSessionIdentity current = new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(current));
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
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
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
    return "http://" + ENVIRONMENT + ".ide.test:" + port + path;
  }

  private String ideWsUrl(int port, String path) {
    return "ws://" + ENVIRONMENT + ".ide.test:" + port + path;
  }

  /** 建立本 Environment 的 READY owner 行：owner 为给定节点实例，租约在未来。 */
  private void registerReadyOwner(UUID ownerNodeId) throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        Statement st = conn.createStatement()) {
      st.execute(
          "insert into environment (id, name, registration_token) values ('"
              + ENVIRONMENT
              + "', 'cross-node-browser-env', 'cross-node-browser-token')"
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
   * 启动非 owner 节点 A：独立内嵌 Tomcat，承载 IDE Origin 入口（host guard + 数据面 + 浏览器 WS 网关）与公开反向 stream 端点。
   *
   * <p>A 自己不做 cookie/代际鉴权，全部单跳交给 owner B；这正是「非 owner 的 IDE Origin 请求先单跳到 owner 再消费
   * bootstrap/cookie」。
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
    ClusterInternalClient client =
        new ClusterInternalClient(HttpClient.newHttpClient(), new ObjectMapper(), authentication);
    ClusterNodeRegistry remoteRegistry =
        environmentId ->
            Optional.of(
                ClusterIdeOwnerRoute.remote(
                    ownerNodeId, URI.create("http://localhost:" + ownerPort)));
    IdeGatewayProperties properties = new IdeGatewayProperties();
    properties.setStreamUrlTemplate("wss://studio-lb" + IdeGatewayProperties.STREAM_PATH);
    IdeOriginProperties originProperties = new IdeOriginProperties();
    originProperties.setOriginTemplate(ORIGIN_TEMPLATE);
    IdeHostResolver hostResolver = new IdeHostResolver(originProperties);
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
        "ideOrigin",
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest request, HttpServletResponse response)
              throws ServletException, IOException {
            Optional<EnvironmentId> environmentId = hostResolver.resolve(request.getHeader("Host"));
            if (environmentId.isEmpty()) {
              response.sendError(HttpServletResponse.SC_NOT_FOUND);
              return;
            }
            String path = request.getRequestURI();
            if (path.equals(originProperties.getBootstrapPath())) {
              dataPlane.handleBootstrap(environmentId.get(), request, response);
              return;
            }
            String query = request.getQueryString();
            String rawPathAndQuery = query == null ? path : path + "?" + query;
            if (IdeBrowserWebSocketGateway.isWebSocketUpgrade(request)) {
              browserGateway.handleUpgrade(environmentId.get(), rawPathAndQuery, request, response);
              return;
            }
            dataPlane.handleHttp(environmentId.get(), rawPathAndQuery, request, response);
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
    context.addServletMappingDecoded("/", "ideOrigin");
    context.addServletMappingDecoded(IdeGatewayProperties.STREAM_PATH + "/*", "ideReverseStream");
    tomcat.start();
    ServerContainer serverContainer =
        (ServerContainer) context.getServletContext().getAttribute(ServerContainer.class.getName());
    serverContainer.setDefaultMaxBinaryMessageBufferSize(16 * 1024 * 1024);
    serverContainer.setDefaultMaxTextMessageBufferSize(16 * 1024 * 1024);
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
      public Optional<IdeSessionIdentity> ideSessionIdentity(EnvironmentId environmentId) {
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
          while (!window.canSend(size)) {
            byte[] ack = inbound.poll(30, TimeUnit.SECONDS);
            if (ack == null || ack[0] != 3) {
              return;
            }
            window.onAck(unsignedInt(ack));
          }
          window.onSend(size);
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

    void awaitStopped() throws InterruptedException {
      worker.join(5_000);
      WebSocket current = socket;
      if (current != null) {
        current.cancel();
      }
      OkHttpClient currentClient = client;
      if (currentClient != null) {
        shutdown(currentClient);
      }
    }
  }

  /** 回 {@code WS_READY} 后按分段 echo 的最小 Daemon peer。 */
  private final class WsEchoDaemon extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final CountDownLatch sawMessage = new CountDownLatch(1);
    private final Thread worker = new Thread(this::serve, "cross-node-browser-ws-daemon");

    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    WsEchoDaemon() {
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

    private void serve() {
      try {
        sawMessage.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }

    void awaitStopped() throws InterruptedException {
      worker.join(5_000);
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
      webSocket.send("{\"type\":\"WS_READY\"}");
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      byte[] frame = bytes.toByteArray();
      if (frame.length < 2) {
        return;
      }
      if (frame[0] == 4 || frame[0] == 5) {
        sawMessage.countDown();
        webSocket.send(ByteString.of(frame));
        webSocket.send(ByteString.of(codec.encodeAck(frame.length - 2)));
      }
    }
  }

  /** bootstrap 消费的真实前置：先由真实 OPEN_VIEW 授权视图，再消费一次性票据换 cookie。 */
  private String openSession(IdeSessionIdentity identity, String viewId, String workspacePath) {
    sessions.viewOpened(identity, viewId, workspacePath);
    return sessions.open(identity, viewId, workspacePath);
  }
}
