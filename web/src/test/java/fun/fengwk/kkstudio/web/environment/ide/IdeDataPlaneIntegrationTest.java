package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonDataBind;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据面的真实 Tomcat HTTP/WS 集成：浏览器入口经过网关过滤器、视图授权、数据连接注册表和真实物理 WebSocket。
 *
 * <p>本类还启动一个非 owner 内嵌节点，验证浏览器 HTTP/WS 只做一跳内部转发；物理 DATA_BIND 连接落在非 owner 时也由该节点整条桥接回 owner。测试中的
 * Daemon 是真实 WebSocket 上的 mux peer，不是直接调用数据面对象。
 */
@Import(IdeDataPlaneIntegrationTest.AuthorizerConfiguration.class)
class IdeDataPlaneIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("55555555-5555-5555-5555-555555555555");

  private static final String SECRET = "data-plane-integration-secret";

  private static final Map<UUID, IdeDataViewAuthorizer.IdeDataViewAccess> ACCESSES =
      new ConcurrentHashMap<>();

  @DynamicPropertySource
  static void clusterProperties(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.cluster.secret", () -> SECRET);
  }

  @LocalServerPort private int ownerPort;

  @MockitoBean private EnvironmentDaemonServer daemonServer;

  @Autowired private JdbcClusterNodeRegistry clusterNodeRegistry;

  @Autowired private IdeGatewayProperties properties;

  @Autowired private IdeDataConnectionRegistry dataConnections;

  @Autowired private IdeBootstrapTicketService bootstrapTickets;

  @Autowired private IdeBrowserSessionRegistry sessionRegistry;

  private final OkHttpClient browser = new OkHttpClient();
  private MuxDaemonPeer daemon;
  private DaemonConnectionIdentity identity;
  private UUID viewId;
  private ForwarderNode forwarder;
  private final AtomicReference<Throwable> webSocketFailure = new AtomicReference<>();
  private final AtomicReference<String> issuedTicket = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    ACCESSES.clear();
    viewId = UUID.randomUUID();
    identity = new DaemonConnectionIdentity(ENVIRONMENT, "daemon-integration");
    ACCESSES.put(
        viewId,
        new IdeDataViewAuthorizer.IdeDataViewAccess(
            identity, viewId, 1L, Set.of("/home/dev/project")));
    registerReadyOwner(clusterNodeRegistry.localNodeId());
    when(daemonServer.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    when(daemonServer.holdsReadyLease(ENVIRONMENT)).thenReturn(true);
    daemon = new MuxDaemonPeer();
    doAnswer(
            invocation -> {
              DaemonDataBind bind = invocation.getArgument(1);
              issuedTicket.set(bind.ticket());
              daemon.dial(bind.streamUrl());
              return null;
            })
        .when(daemonServer)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));
    properties.setDataStreamBaseUrl("http://127.0.0.1:" + ownerPort);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (daemon != null) {
      daemon.close();
    }
    if (forwarder != null) {
      forwarder.close();
      forwarder = null;
    }
    if (identity != null) {
      dataConnections.closeGeneration(identity);
      sessionRegistry.closeGeneration(identity);
    }
    browser.dispatcher().executorService().shutdown();
    browser.connectionPool().evictAll();
    ACCESSES.clear();
    webSocketFailure.set(null);
  }

  @Test
  void servesHttpAndWebSocketOnOnePhysicalDataConnection() throws Exception {
    String cookie = "kk_ide_session=test-" + viewId;
    try (Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(ownerUrl("/environment-ide/" + ENVIRONMENT + "/" + viewId + "/hello"))
                    .header("Cookie", cookie)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Accept-Language", "zh-CN")
                    .header("X-KK-Origin-Scheme", "https")
                    .build())
            .execute()) {
      assertEquals(200, response.code());
      assertEquals("hello", response.body().string());
    }
    assertTrue(daemon.awaitCompleted(10), "HTTP COMPLETE must follow browser flush");

    assertTrue(daemon.awaitConnected(10));
    assertEquals(1, daemon.opens().size());
    assertEquals(
        "/__kk/ide/view/" + viewId + "/1/hello", daemon.opens().getFirst().request().path());
    List<MuxHeader> firstHeaders = daemon.opens().getFirst().request().headers();
    assertTrue(
        firstHeaders.stream()
            .anyMatch(
                header ->
                    header.name().equalsIgnoreCase("accept-language")
                        && header.value().equals("zh-CN")),
        "safe browser headers must reach the mux stream");
    assertFalse(
        firstHeaders.stream()
            .anyMatch(
                header ->
                    header.name().equalsIgnoreCase("cookie")
                        || header.name().equalsIgnoreCase("x-kk-origin-scheme")),
        "browser credentials and internal headers must not reach code-server");

    String echoed =
        sendWebSocket(
            ownerUrl("/environment-ide/" + ENVIRONMENT + "/" + viewId + "/ws"),
            cookie,
            "hello over ws");
    assertEquals("hello over ws", echoed);
    assertEquals(2, daemon.opens().size());
    assertEquals(1, daemon.connectionCount(), "one Environment keeps one physical data connection");
    assertTrue(daemon.failure() == null, () -> "mux peer failure: " + daemon.failure());

    int replayCode =
        closeCodeOf(
            "ws://127.0.0.1:"
                + ownerPort
                + IdeGatewayProperties.DATA_PATH
                + "/"
                + ENVIRONMENT
                + "?ticket="
                + issuedTicket.get());
    assertNotEquals(1000, replayCode, "a consumed DATA_BIND ticket must not be reusable");
  }

  @Test
  void forwardsHttpAndWebSocketThroughNonOwnerNode() throws Exception {
    forwarder = ForwarderNode.start(ownerPort, clusterNodeRegistry.localNodeId(), ENVIRONMENT);
    properties.setDataStreamBaseUrl(forwarder.url());
    String cookie = "kk_ide_session=test-" + viewId;

    try (Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(
                        forwarder.url()
                            + "/environment-ide/"
                            + ENVIRONMENT
                            + "/"
                            + viewId
                            + "/echo")
                    .header("Cookie", cookie)
                    .header("Sec-Fetch-Site", "same-origin")
                    .post(RequestBody.create("cross-node", MediaType.parse("text/plain")))
                    .header("Origin", forwarder.origin())
                    .build())
            .execute()) {
      assertEquals(200, response.code());
      assertEquals("cross-node", response.body().string());
      assertNull(
          response.header("Set-Cookie"), "ordinary data responses must filter upstream cookies");
    }
    assertTrue(daemon.awaitCompleted(10), "HTTP COMPLETE must follow browser flush");

    assertTrue(daemon.awaitConnected(10));
    assertEquals(1, daemon.opens().size());
    assertEquals(
        "/__kk/ide/view/" + viewId + "/1/echo", daemon.opens().getFirst().request().path());
    assertTrue(dataConnections.activeConnection(ENVIRONMENT).isPresent());
    assertFalse(
        daemon.opens().getFirst().request().headers().stream()
            .anyMatch(header -> header.name().equalsIgnoreCase("cookie")),
        "the Studio session cookie must be consumed by the owner, not code-server");

    String payload = "世界".repeat(40_000);
    String echoed =
        sendWebSocket(
            forwarder.url() + "/environment-ide/" + ENVIRONMENT + "/" + viewId + "/ws",
            cookie,
            payload);
    assertEquals(payload, echoed);
    List<MuxDaemonPeer.WsSegment> segments = daemon.wsSegments();
    assertTrue(segments.size() > 1, "large browser message must be mux-fragmented");
    assertEquals(
        1,
        segments.stream().filter(MuxDaemonPeer.WsSegment::fin).count(),
        "exactly the final segment carries FIN");
    assertTrue(segments.stream().allMatch(MuxDaemonPeer.WsSegment::text));
    assertTrue(segments.getLast().fin());
    assertEquals(2, daemon.opens().size());
    assertEquals(1, daemon.connectionCount());
    assertTrue(daemon.failure() == null, () -> "mux peer failure: " + daemon.failure());
  }

  @Test
  void forwardsBootstrapAndSessionThroughNonOwnerNodePreservingSetCookie() throws Exception {
    forwarder = ForwarderNode.start(ownerPort, clusterNodeRegistry.localNodeId(), ENVIRONMENT);
    String workspace = "/home/dev/project";
    sessionRegistry.viewOpened(identity, viewId.toString(), 1L, workspace);
    String token = bootstrapTickets.issue(identity, viewId.toString(), 1L, workspace);
    OkHttpClient noRedirect = browser.newBuilder().followRedirects(false).build();
    String cookie;
    try {
      try (Response response =
          noRedirect
              .newCall(
                  new Request.Builder()
                      .url(
                          forwarder.url()
                              + "/environment-ide/"
                              + ENVIRONMENT
                              + "/"
                              + viewId
                              + "/__ide/bootstrap?token="
                              + token)
                      .header("Origin", forwarder.origin())
                      .build())
              .execute()) {
        assertEquals(303, response.code());
        String setCookie = response.header("Set-Cookie");
        assertNotNull(setCookie, "management forward must preserve the owner session cookie");
        assertTrue(setCookie.startsWith("kk_ide_session="));
        assertTrue(
            setCookie.contains("; Path=/environment-ide/" + ENVIRONMENT + "/" + viewId + "/"));
        assertEquals(
            "/environment-ide/"
                + ENVIRONMENT
                + "/"
                + viewId
                + "/?workspace=%2Fhome%2Fdev%2Fproject",
            response.header("Location"));
        cookie = setCookie.substring(0, setCookie.indexOf(';'));
      }
      try (Response response =
          noRedirect
              .newCall(
                  new Request.Builder()
                      .url(
                          forwarder.url()
                              + "/environment-ide/"
                              + ENVIRONMENT
                              + "/"
                              + viewId
                              + "/__ide/session")
                      .header("Cookie", cookie)
                      .build())
              .execute()) {
        assertEquals(204, response.code());
      }
      assertTrue(daemon.opens().isEmpty(), "management entries must not open a data stream");
    } finally {
      noRedirect.dispatcher().executorService().shutdown();
      noRedirect.connectionPool().evictAll();
    }
  }

  @Test
  void rejectsCrossOriginAndEscapingViewPaths() throws Exception {
    String cookie = "kk_ide_session=test-" + viewId;
    try (Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(ownerUrl("/environment-ide/" + ENVIRONMENT + "/" + viewId + "/hello"))
                    .header("Cookie", cookie)
                    .header("Origin", "https://evil.example")
                    .build())
            .execute()) {
      assertEquals(403, response.code());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> IdeDataInternalPaths.upstreamPath(viewId, 1L, "/../outside"));
    assertThrows(
        IllegalArgumentException.class,
        () -> IdeDataInternalPaths.upstreamPath(viewId, 1L, "/%2e%2e/outside"));
    assertTrue(daemon.opens().isEmpty(), "rejected requests must not create a mux stream");
  }

  private int closeCodeOf(String url) throws Exception {
    CountDownLatch closed = new CountDownLatch(1);
    AtomicReference<Integer> code = new AtomicReference<>();
    AtomicReference<Boolean> opened = new AtomicReference<>(false);
    WebSocket socket =
        browser.newWebSocket(
            new Request.Builder().url(url).build(),
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket webSocket, Response response) {
                opened.set(true);
              }

              @Override
              public void onClosing(WebSocket webSocket, int closeCode, String reason) {
                code.set(closeCode);
                closed.countDown();
              }

              @Override
              public void onClosed(WebSocket webSocket, int closeCode, String reason) {
                code.set(closeCode);
                closed.countDown();
              }

              @Override
              public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                closed.countDown();
              }
            });
    assertTrue(
        closed.await(10, TimeUnit.SECONDS),
        () ->
            "physical data connection must close; opened="
                + opened
                + "; ticket="
                + issuedTicket.get());
    socket.cancel();
    return code.get() == null ? -1 : code.get();
  }

  private String sendWebSocket(String url, String cookie, String payload) throws Exception {
    CountDownLatch opened = new CountDownLatch(1);
    CountDownLatch echoed = new CountDownLatch(1);
    AtomicReference<String> message = new AtomicReference<>();
    AtomicReference<String> closeReason = new AtomicReference<>();
    WebSocket socket =
        browser.newWebSocket(
            new Request.Builder()
                .url(url.replaceFirst("^http", "ws"))
                .header("Cookie", cookie)
                .header("Origin", url.substring(0, url.indexOf("/environment-ide")))
                .build(),
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket webSocket, Response response) {
                opened.countDown();
              }

              @Override
              public void onMessage(WebSocket webSocket, String text) {
                message.set(text);
                echoed.countDown();
              }

              @Override
              public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                webSocketFailure.compareAndSet(null, error);
                opened.countDown();
              }

              @Override
              public void onClosed(WebSocket webSocket, int code, String reason) {
                closeReason.set(code + ":" + reason);
              }
            });
    assertTrue(opened.await(10, TimeUnit.SECONDS), "browser websocket must open");
    assertTrue(socket.send(payload));
    assertTrue(
        echoed.await(10, TimeUnit.SECONDS),
        () ->
            "websocket echo timeout: failure="
                + webSocketFailure.get()
                + " close="
                + closeReason.get()
                + " opens="
                + daemon.opens());
    socket.close(1000, "done");
    return message.get();
  }

  private String ownerUrl(String path) {
    return "http://127.0.0.1:" + ownerPort + path;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private void registerReadyOwner(UUID ownerNodeId) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          "insert into environment (id, name, registration_token_hash) values ('"
              + ENVIRONMENT
              + "', 'data-plane-integration', repeat('b', 64))"
              + " on conflict (id) do nothing");
      statement.execute(
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

  /** 独立非 owner 节点：只装配网关入口与公共物理数据端点，不承载 owner 内部 controller。 */
  private static final class ForwarderNode implements AutoCloseable {

    private final Tomcat tomcat;
    private final int port;
    private final ScheduledExecutorService heartbeat;

    private ForwarderNode(Tomcat tomcat, int port, ScheduledExecutorService heartbeat) {
      this.tomcat = tomcat;
      this.port = port;
      this.heartbeat = heartbeat;
    }

    static ForwarderNode start(int ownerPort, UUID ownerNodeId, EnvironmentId environmentId)
        throws Exception {
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
            public Optional<ClusterOwnerRoute> route(EnvironmentId ignored) {
              return Optional.of(
                  ClusterOwnerRoute.remote(
                      ownerNodeId, URI.create("http://127.0.0.1:" + ownerPort)));
            }

            @Override
            public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
              return Optional.empty();
            }
          };
      IdeGatewayProperties properties = new IdeGatewayProperties();
      IdeRemoteDataPlaneForwarder forwarder = new IdeRemoteDataPlaneForwarder(client, properties);
      IdeGatewayDataPlane dataPlane =
          new IdeGatewayDataPlane(
              mock(IdeCommandTransport.class),
              properties,
              remoteRegistry,
              forwarder,
              mock(IdeDataConnectionRegistry.class),
              null,
              new IdeBrowserSessionHandler(
                  mock(IdeBootstrapTicketService.class),
                  mock(IdeBrowserSessionRegistry.class),
                  mock(IdeCommandTransport.class),
                  properties));
      StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
      IdeBrowserWebSocketGateway browserGateway =
          new IdeBrowserWebSocketGateway(
              properties,
              mock(IdeDataConnectionRegistry.class),
              remoteRegistry,
              client,
              webSocketClient,
              Optional.empty());
      IdeGatewayFilter filter = new IdeGatewayFilter(dataPlane, browserGateway);
      ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
      IdeDataPhysicalWebSocketHandler publicData =
          new IdeDataPhysicalWebSocketHandler(
              mock(IdeDataConnectionRegistry.class),
              remoteRegistry,
              client,
              webSocketClient,
              heartbeat,
              properties,
              false);

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
      DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();
      Tomcat.addServlet(
          context,
          "ideDataPhysical",
          new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
              handshakeHandler.setServletContext(request.getServletContext());
              handshakeHandler.start();
              new WebSocketHttpRequestHandler(publicData, handshakeHandler)
                  .handleRequest(request, response);
            }
          });
      context.addServletMappingDecoded("/environment-ide/*", "ideGateway");
      context.addServletMappingDecoded(IdeGatewayProperties.DATA_PATH + "/*", "ideDataPhysical");
      tomcat.start();
      return new ForwarderNode(tomcat, tomcat.getConnector().getLocalPort(), heartbeat);
    }

    String url() {
      return "http://127.0.0.1:" + port;
    }

    String origin() {
      return url();
    }

    @Override
    public void close() throws Exception {
      heartbeat.shutdownNow();
      tomcat.stop();
      tomcat.destroy();
    }
  }

  /** 测试授权端口：cookie 只绑定一个 view/epoch，owner 内部入口也复用同一授权。 */
  @TestConfiguration(proxyBeanMethods = false)
  static class AuthorizerConfiguration {

    @Bean
    IdeDataViewAuthorizer testIdeDataViewAuthorizer() {
      return (environmentId, requestedViewId, cookieHeader) -> {
        if (!ENVIRONMENT.equals(environmentId) || cookieHeader == null) {
          return Optional.empty();
        }
        String expected = "kk_ide_session=test-" + requestedViewId;
        for (String part : cookieHeader.split(";")) {
          if (!expected.equals(part.trim())) {
            continue;
          }
          return Optional.ofNullable(ACCESSES.get(requestedViewId))
              .filter(access -> access.controlIdentity().environmentId().equals(environmentId));
        }
        return Optional.empty();
      };
    }
  }
}
