package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeViewSnapshotStore;
import fun.fengwk.kkstudio.ide.cluster.ClusterEnvironmentIdeConfiguration;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioOriginPolicy;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.cluster.ClusterProperties;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnels;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelStream;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 真实嵌入式 Jetty 容器集成测试：
 *
 * <ul>
 *   <li>双节点（Node 1 远程转发，Node 2 Owner 节点）HTTP 流式与 WebSocket 全双工透传
 *   <li>WebSocket 客户端使用 Jetty 官方 {@link WebSocketCoreClient}
 *   <li>半关闭（half-close）正常传播
 *   <li>非 101 握手响应只返回 HTTP 状态码并关闭连接
 *   <li>Owner 本地大响应在客户端慢读条件下保持有界内存传输
 *   <li>{@link JettyConnectionHandover} 在真实 Jetty EE11 上下文与异常分支下的完整验证
 *   <li>节点间转发走真实 TLS：证书在测试运行时由 JDK {@code keytool} 生成，owner 是 Jetty TLS 服务端，客户端信任来自真实 Spring Boot
 *       SSL bundle；内部 HTTP 与 WebSocket 转发在证书受信且主机名匹配时成功，主机名不匹配或证书不受信时失败
 * </ul>
 */
class IdeGatewayJettyIntegrationTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID USER_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final StudioPrincipal PRINCIPAL =
      new StudioPrincipal(USER_ID, "test", StudioRole.USER);
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-owner-1");

  private static Server node1Server;
  private static Server node2Server;
  private static int node1Port;
  private static int node2Port;

  /** 生产配置 {@code kk-studio.cluster.ssl-bundle} 使用的 bundle 名。 */
  private static final String CLUSTER_BUNDLE_NAME = "cluster";

  private static final String KEY_STORE_PASSWORD = "changeit";

  /** 受信证书覆盖的主机名；转发目标解析到 {@code 127.0.0.1}。 */
  private static final String TLS_HOST = "localhost";

  @TempDir static Path tlsDir;

  private static String sessionCookie;
  private static TestEnvironmentTunnels ownerTunnels;
  private static WebSocketCoreClient wsClient;
  private static HttpClient httpClient;
  private static IdeBootstrapTicketService ticketService;
  private static IdeDataViewAuthorizer authorizer;
  private static IdeBrowserSessionHandler sessionHandler;
  private static IdeClusterClient clusterClient;

  private static SslBundles sslBundles;
  private static int tlsOwnerPort;
  private static int tlsGatewayPort;
  private static Server tlsOwnerServer;
  private static Server tlsGatewayServer;
  private static final AtomicReference<ForwardingTarget> forwardingTarget = new AtomicReference<>();
  private static final List<HttpClient> tlsClients = new ArrayList<>();

  @BeforeAll
  static void startServers() throws Exception {
    httpClient = HttpClient.newHttpClient();
    wsClient = new WebSocketCoreClient();
    wsClient.start();

    // 1. Session & View Authorization
    IdeBrowserSessionRegistry registry = new IdeBrowserSessionRegistry();
    IdeViewSnapshotStore snapshots =
        new IdeViewSnapshotStore(environmentId -> Optional.of(IDENTITY));
    snapshots.accept(
        IDENTITY,
        1L,
        List.of(
            new IdeViewSnapshotStore.View(
                VIEW,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                "/managed/dir",
                "/managed/workspace",
                IdeViewState.OPEN)));
    sessionCookie = registry.open(IDENTITY, VIEW.toString(), 1L, "/managed/workspace", USER_ID);

    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(ENV)).thenReturn(Optional.of(IDENTITY));
    authorizer = new SessionIdeDataViewAuthorizer(registry, snapshots, transport);

    ticketService = mock(IdeBootstrapTicketService.class);
    sessionHandler = new IdeBrowserSessionHandler(ticketService, registry, snapshots, transport);
    clusterClient = mock(IdeClusterClient.class);
    when(clusterClient.signHeaders(any(), any(), any()))
        .thenReturn(Map.of("X-KK-Cluster-Signature", "sig123"));

    // 2. Start Node 2 (Owner)
    node2Server = new Server();
    ServerConnector node2Connector = new ServerConnector(node2Server);
    node2Connector.setPort(0);
    node2Server.addConnector(node2Connector);

    ownerTunnels = new TestEnvironmentTunnels();

    ClusterNodeRegistry node2ClusterRegistry = mock(ClusterNodeRegistry.class);
    when(node2ClusterRegistry.route(ENV))
        .thenAnswer(inv -> Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    IdeRemoteDataPlaneForwarder node2Forwarder =
        new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);
    IdeGatewayDataPlane node2DataPlane =
        new IdeGatewayDataPlane(
            node2ClusterRegistry, ownerTunnels, authorizer, sessionHandler, node2Forwarder);

    ServletContextHandler node2Context = new ServletContextHandler();
    node2Context.setContextPath("/");
    node2Context.addServlet(
        new ServletHolder(new InternalServlet(node2DataPlane)), "/internal/ide/environments/*");
    node2Context.addServlet(new ServletHolder(new GatewayServlet(node2DataPlane)), "/ide/*");
    node2Server.setHandler(node2Context);
    node2Server.start();
    node2Port = node2Connector.getLocalPort();

    // 3. Start Node 1 (Remote Forwarder)
    node1Server = new Server();
    ServerConnector node1Connector = new ServerConnector(node1Server);
    node1Connector.setPort(0);
    node1Server.addConnector(node1Connector);

    ClusterNodeRegistry node1ClusterRegistry = mock(ClusterNodeRegistry.class);
    when(node1ClusterRegistry.route(ENV))
        .thenAnswer(
            inv ->
                Optional.of(
                    ClusterOwnerRoute.remote(
                        UUID.randomUUID(), URI.create("http://127.0.0.1:" + node2Port))));

    IdeRemoteDataPlaneForwarder node1Forwarder =
        new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);
    IdeGatewayDataPlane node1DataPlane =
        new IdeGatewayDataPlane(
            node1ClusterRegistry, ownerTunnels, authorizer, sessionHandler, node1Forwarder);

    ServletContextHandler node1Context = new ServletContextHandler();
    node1Context.setContextPath("/");
    node1Context.addServlet(new ServletHolder(new GatewayServlet(node1DataPlane)), "/ide/*");
    node1Server.setHandler(node1Context);
    node1Server.start();
    node1Port = node1Connector.getLocalPort();

    // 4. Start the TLS owner and the TLS forwarding gateway
    startTlsOwnerAndGateway(node2DataPlane);
  }

  @AfterAll
  static void stopServers() throws Exception {
    if (wsClient != null) {
      wsClient.stop();
    }
    for (HttpClient client : tlsClients) {
      client.close();
    }
    tlsClients.clear();
    if (tlsOwnerServer != null) {
      tlsOwnerServer.stop();
    }
    if (tlsGatewayServer != null) {
      tlsGatewayServer.stop();
    }
    if (node1Server != null) {
      node1Server.stop();
    }
    if (node2Server != null) {
      node2Server.stop();
    }
  }

  @Test
  void twoNodeHttpForwarding() throws Exception {
    String reqPayload = "{\"message\":\"hello from client\"}";
    String respPayload = "{\"status\":\"saved\"}";

    ownerTunnels.setTunnel(
        new SimpleEnvironmentTunnel(
            IDENTITY,
            req -> {
              SimpleTunnelExchange exchange = new SimpleTunnelExchange(req);
              Thread.ofVirtual()
                  .start(
                      () -> {
                        try {
                          ByteBuffer chunk =
                              exchange.peer().read().toCompletableFuture().get(5, TimeUnit.SECONDS);
                          assertNotNull(chunk);
                          byte[] b = new byte[chunk.remaining()];
                          chunk.get(b);
                          assertEquals(reqPayload, new String(b, StandardCharsets.UTF_8));

                          exchange.respond(
                              new TunnelExchange.Response(
                                  200, Map.of("Content-Type", List.of("application/json"))));
                          exchange
                              .peer()
                              .write(ByteBuffer.wrap(respPayload.getBytes(StandardCharsets.UTF_8)))
                              .toCompletableFuture()
                              .get(5, TimeUnit.SECONDS);
                          exchange
                              .peer()
                              .endOutput()
                              .toCompletableFuture()
                              .get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                          exchange.close();
                        }
                      });
              return exchange;
            },
            req -> {
              throw new UnsupportedOperationException();
            }));

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + node1Port + "/ide/" + ENV + "/" + VIEW + "/data/save"))
            .POST(HttpRequest.BodyPublishers.ofString(reqPayload))
            .header("Origin", "http://127.0.0.1:" + node1Port)
            .header("Cookie", IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie)
            .header("Content-Type", "application/json")
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertEquals(respPayload, response.body());
  }

  @Test
  void twoNodeWebSocketForwardingAndFullDuplexEcho() throws Exception {
    ownerTunnels.setTunnel(
        new SimpleEnvironmentTunnel(
            IDENTITY,
            req -> {
              throw new UnsupportedOperationException();
            },
            req -> {
              SimpleTunnelExchange exchange = new SimpleTunnelExchange(req);
              String accept = computeAcceptHeader(req);
              Thread.ofVirtual()
                  .start(
                      () -> {
                        exchange.respond(
                            new TunnelExchange.Response(
                                200, Map.of("Sec-WebSocket-Accept", List.of(accept))));
                        try {
                          ByteBuffer buf =
                              exchange
                                  .peer()
                                  .read()
                                  .toCompletableFuture()
                                  .get(10, TimeUnit.SECONDS);
                          if (buf != null) {
                            exchange
                                .peer()
                                .write(serverTextFrame("echo-test"))
                                .toCompletableFuture()
                                .join();
                          }
                        } catch (Exception ignored) {
                        }
                      });
              return exchange;
            }));

    TestFrameHandler clientHandler = new TestFrameHandler();
    URI wsUri =
        URI.create("ws://127.0.0.1:" + node1Port + "/ide/" + ENV + "/" + VIEW + "/ws-terminal");

    CoreClientUpgradeRequest upgradeRequest =
        CoreClientUpgradeRequest.from(wsClient, wsUri, clientHandler);
    upgradeRequest.headers(
        h -> {
          h.add("Origin", "http://127.0.0.1:" + node1Port);
          h.add("Cookie", IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie);
        });

    CompletableFuture<CoreSession> sessionFuture = upgradeRequest.sendAsync();
    CoreSession session = sessionFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(session);

    // Send a frame from client and expect echo from owner peer
    session.sendFrame(new Frame(OpCode.TEXT, "echo-test"), Callback.NOOP, false);
    Frame echo = clientHandler.nextFrame(5, TimeUnit.SECONDS);
    assertNotNull(echo);
    assertEquals(OpCode.TEXT, echo.getOpCode());
    assertEquals("echo-test", echo.getPayloadAsUTF8());

    session.close(1000, "normal", Callback.NOOP);
  }

  @Test
  void webSocketHalfClosePropagation() throws Exception {
    ownerTunnels.setTunnel(
        new SimpleEnvironmentTunnel(
            IDENTITY,
            req -> {
              throw new UnsupportedOperationException();
            },
            req -> {
              SimpleTunnelExchange exchange = new SimpleTunnelExchange(req);
              String accept = computeAcceptHeader(req);
              Thread.ofVirtual()
                  .start(
                      () -> {
                        exchange.respond(
                            new TunnelExchange.Response(
                                200, Map.of("Sec-WebSocket-Accept", List.of(accept))));
                        try {
                          // Read initial frame
                          ByteBuffer buf =
                              exchange.peer().read().toCompletableFuture().get(5, TimeUnit.SECONDS);
                          if (buf != null) {
                            exchange
                                .peer()
                                .write(serverTextFrame("test-data"))
                                .toCompletableFuture()
                                .join();
                          }
                          // Tunnel initiates half-close
                          exchange.peer().endOutput().toCompletableFuture().join();
                        } catch (Exception ignored) {
                        }
                      });
              return exchange;
            }));

    TestFrameHandler clientHandler = new TestFrameHandler();
    URI wsUri =
        URI.create("ws://127.0.0.1:" + node1Port + "/ide/" + ENV + "/" + VIEW + "/ws-terminal");

    CoreClientUpgradeRequest upgradeRequest =
        CoreClientUpgradeRequest.from(wsClient, wsUri, clientHandler);
    upgradeRequest.headers(
        h -> {
          h.add("Origin", "http://127.0.0.1:" + node1Port);
          h.add("Cookie", IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie);
        });

    CoreSession session = upgradeRequest.sendAsync().get(5, TimeUnit.SECONDS);
    session.sendFrame(new Frame(OpCode.TEXT, "test-data"), Callback.NOOP, false);

    Frame echo = clientHandler.nextFrame(5, TimeUnit.SECONDS);
    assertNotNull(echo);
    assertEquals("test-data", echo.getPayloadAsUTF8());

    // Wait for close notification triggered by tunnel endOutput
    Boolean closed = clientHandler.closedFuture.get(5, TimeUnit.SECONDS);
    assertTrue(closed);
  }

  @Test
  void non101OnlyReturnsStatusWithoutUpgrade() throws Exception {
    // 1. Remote rejection (Node 2 authorizer rejects invalid cookie with 401)
    int remoteStatus =
        sendRawWebSocketUpgrade(
            node1Port,
            "/ide/" + ENV + "/" + VIEW + "/ws-terminal",
            IdeGatewayConstants.SESSION_COOKIE_NAME + "=bad-cookie");
    assertEquals(401, remoteStatus);

    // 2. Local rejection (Node 2 local tunnel returns 503)
    ownerTunnels.setTunnel(
        new SimpleEnvironmentTunnel(
            IDENTITY,
            req -> {
              throw new UnsupportedOperationException();
            },
            req -> {
              SimpleTunnelExchange exchange = new SimpleTunnelExchange(req);
              exchange.respond(new TunnelExchange.Response(503, Map.of()));
              return exchange;
            }));

    int localStatus =
        sendRawWebSocketUpgrade(
            node2Port,
            "/ide/" + ENV + "/" + VIEW + "/ws-terminal",
            IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie);
    assertEquals(503, localStatus);
  }

  private int sendRawWebSocketUpgrade(int port, String path, String cookie) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      OutputStream out = socket.getOutputStream();
      String req =
          "GET "
              + path
              + " HTTP/1.1\r\n"
              + "Host: 127.0.0.1:"
              + port
              + "\r\n"
              + "Connection: Upgrade\r\n"
              + "Upgrade: websocket\r\n"
              + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
              + "Sec-WebSocket-Version: 13\r\n"
              + "Origin: http://127.0.0.1:"
              + port
              + "\r\n"
              + (cookie != null ? "Cookie: " + cookie + "\r\n" : "")
              + "\r\n";
      out.write(req.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
      String statusLine = reader.readLine();
      assertNotNull(statusLine);
      String[] parts = statusLine.split(" ");
      return Integer.parseInt(parts[1]);
    }
  }

  @Test
  void ownerLocalLargeResponseSlowReadBoundedMemory() throws Exception {
    int totalChunks = 80;
    int chunkSize = 32 * 1024; // 32 KB * 80 = 2.56 MB
    AtomicInteger activeReads = new AtomicInteger();
    AtomicInteger maxConcurrentReads = new AtomicInteger();
    AtomicInteger chunksServed = new AtomicInteger();

    ownerTunnels.setTunnel(
        new SimpleEnvironmentTunnel(
            IDENTITY,
            req -> {
              SimpleTunnelExchange exchange = new SimpleTunnelExchange(req);
              exchange.setCustomStream(
                  new TunnelStream() {
                    @Override
                    public CompletionStage<ByteBuffer> read() {
                      int cur = activeReads.incrementAndGet();
                      maxConcurrentReads.updateAndGet(m -> Math.max(m, cur));
                      activeReads.decrementAndGet();

                      int index = chunksServed.getAndIncrement();
                      if (index < totalChunks) {
                        byte[] data = new byte[chunkSize];
                        data[0] = (byte) (index & 0xFF);
                        return CompletableFuture.completedFuture(ByteBuffer.wrap(data));
                      }
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> write(ByteBuffer source) {
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> endOutput() {
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void close() {}
                  });

              exchange.respond(
                  new TunnelExchange.Response(
                      200, Map.of("Content-Type", List.of("application/octet-stream"))));
              return exchange;
            },
            req -> {
              throw new UnsupportedOperationException();
            }));

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + node2Port
                        + "/ide/"
                        + ENV
                        + "/"
                        + VIEW
                        + "/large-download"))
            .header("Origin", "http://127.0.0.1:" + node2Port)
            .header("Cookie", IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie)
            .GET()
            .build();

    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    // Slow read simulation: read in 1KB blocks with slight delays
    try (InputStream in = response.body()) {
      byte[] buf = new byte[1024];
      int totalRead = 0;
      int r;
      while ((r = in.read(buf)) >= 0) {
        totalRead += r;
        Thread.sleep(1);
      }
      assertEquals(totalChunks * chunkSize, totalRead);
    }

    // Verify bounded memory: stream.read() is called strictly sequentially by the pump
    assertEquals(1, maxConcurrentReads.get());
    assertEquals(totalChunks + 1, chunksServed.get());
  }

  @Test
  void nonOwnerBootstrapPreservesCookieAndLocation() throws Exception {
    when(ticketService.consume("bootstrap-token-1"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(
                    IDENTITY, VIEW.toString(), 1L, "/managed/workspace", USER_ID)));

    // Test 1: plain http request to Node 1 (non-owner)
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + node1Port
                        + "/ide/"
                        + ENV
                        + "/"
                        + VIEW
                        + "/__ide/bootstrap?token=bootstrap-token-1"))
            .header("Origin", "http://127.0.0.1:" + node1Port)
            .header("Sec-Fetch-Site", "same-origin")
            .GET()
            .build();

    HttpResponse<Void> response =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.discarding());

    assertEquals(303, response.statusCode());
    Optional<String> setCookie = response.headers().firstValue("Set-Cookie");
    assertTrue(setCookie.isPresent());
    assertTrue(setCookie.get().contains(IdeGatewayConstants.SESSION_COOKIE_NAME + "="));
    assertTrue(setCookie.get().contains("Path=/ide/" + ENV + "/" + VIEW + "/"));
    assertTrue(setCookie.get().contains("HttpOnly"));
    assertTrue(setCookie.get().contains("SameSite=Strict"));
    assertFalse(setCookie.get().contains("Secure"));

    Optional<String> location = response.headers().firstValue("Location");
    assertTrue(location.isPresent());
    String expectedLocation = "/ide/" + ENV + "/" + VIEW + "/?workspace=%2Fmanaged%2Fworkspace";
    assertEquals(expectedLocation, location.get());

    // Test 2: X-Forwarded-Proto: https -> cookie has Secure flag
    when(ticketService.consume("bootstrap-token-2"))
        .thenReturn(
            Optional.of(
                new IdeBootstrapTicketService.Ticket(
                    IDENTITY, VIEW.toString(), 1L, "/managed/workspace", USER_ID)));

    HttpRequest secureRequest =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + node1Port
                        + "/ide/"
                        + ENV
                        + "/"
                        + VIEW
                        + "/__ide/bootstrap?token=bootstrap-token-2"))
            .header("Origin", "http://127.0.0.1:" + node1Port)
            .header("Sec-Fetch-Site", "same-origin")
            .header("X-Forwarded-Proto", "https")
            .GET()
            .build();

    HttpResponse<Void> secureResponse =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(secureRequest, HttpResponse.BodyHandlers.discarding());

    assertEquals(303, secureResponse.statusCode());
    Optional<String> secureSetCookie = secureResponse.headers().firstValue("Set-Cookie");
    assertTrue(secureSetCookie.isPresent());
    assertTrue(secureSetCookie.get().contains("; Secure"));
  }

  @Test
  void jettyConnectionHandoverValidation() {
    assertThrows(
        NullPointerException.class,
        () -> JettyConnectionHandover.handover(null, 101, Map.of(), mock(TunnelStream.class)));
    assertThrows(
        NullPointerException.class,
        () ->
            JettyConnectionHandover.handover(mock(HttpServletRequest.class), 101, Map.of(), null));

    // Outside Jetty context
    HttpServletRequest nonJettyReq = mock(HttpServletRequest.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            JettyConnectionHandover.handover(nonJettyReq, 101, Map.of(), mock(TunnelStream.class)));
  }

  private static ByteBuffer serverTextFrame(String text) {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    ByteBuffer frame = ByteBuffer.allocate(2 + payload.length);
    frame.put((byte) 0x81); // FIN + TEXT opcode
    frame.put((byte) payload.length); // MASK = 0, length
    frame.put(payload);
    frame.flip();
    return frame;
  }

  private static String computeAcceptHeader(TunnelHttpRequest req) {
    String key = null;
    if (req.headers() != null) {
      for (Map.Entry<String, List<String>> entry : req.headers().entrySet()) {
        if ("sec-websocket-key".equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
          key = entry.getValue().get(0);
          break;
        }
      }
    }
    if (key == null) {
      return "";
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] digest =
          md.digest(
              (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
      return Base64.getEncoder().encodeToString(digest);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // --- Node-to-node TLS ---

  /**
   * 运行时用 JDK {@code keytool} 生成一张只覆盖 {@code localhost} 的自签证书，并启动真实 Jetty TLS owner 与常驻 TLS 网关。
   *
   * <p>TLS owner 复用 Node 2 的 owner 数据面；owner 强制 SNI；客户端信任来自真实 Spring Boot bundle，经 {@code
   * kk-studio.cluster.ssl-bundle} 的 bundle 名解析。
   */
  private static void startTlsOwnerAndGateway(IdeGatewayDataPlane ownerDataPlane) throws Exception {
    Path keyStore = tlsDir.resolve("cluster.p12");
    generateSelfSignedCertificate(keyStore);
    sslBundles =
        new DefaultSslBundleRegistry(
            CLUSTER_BUNDLE_NAME, SslBundle.of(SslStoreBundle.of(null, null, trustStore(keyStore))));

    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(keyStore.toString());
    sslContextFactory.setKeyStorePassword(KEY_STORE_PASSWORD);
    sslContextFactory.setKeyManagerPassword(KEY_STORE_PASSWORD);
    sslContextFactory.setSniRequired(true);

    tlsOwnerServer = new Server();
    ServerConnector ownerConnector = new ServerConnector(tlsOwnerServer, sslContextFactory);
    ownerConnector.setHost("127.0.0.1");
    ownerConnector.setPort(0);
    tlsOwnerServer.addConnector(ownerConnector);
    ServletContextHandler ownerContext = new ServletContextHandler();
    ownerContext.setContextPath("/");
    ownerContext.addServlet(
        new ServletHolder(new InternalServlet(ownerDataPlane)), "/internal/ide/environments/*");
    tlsOwnerServer.setHandler(ownerContext);
    tlsOwnerServer.start();
    tlsOwnerPort = ownerConnector.getLocalPort();

    tlsGatewayServer = new Server();
    ServerConnector gatewayConnector = new ServerConnector(tlsGatewayServer);
    gatewayConnector.setHost("127.0.0.1");
    gatewayConnector.setPort(0);
    tlsGatewayServer.addConnector(gatewayConnector);
    ServletContextHandler gatewayContext = new ServletContextHandler();
    gatewayContext.setContextPath("/");
    gatewayContext.addServlet(new ServletHolder(new TlsForwardingServlet()), "/tls-ide/*");
    tlsGatewayServer.setHandler(gatewayContext);
    tlsGatewayServer.start();
    tlsGatewayPort = gatewayConnector.getLocalPort();
  }

  /** 生产装配：bundle 名经 {@code kk-studio.cluster.ssl-bundle} 解析，由生产 bean 工厂建出共享客户端。 */
  private static HttpClient clusterInternalHttpClient(SslBundles bundles) {
    ClusterProperties properties = new ClusterProperties();
    properties.setSslBundle(CLUSTER_BUNDLE_NAME);
    StaticListableBeanFactory beanFactory =
        new StaticListableBeanFactory(Map.of("sslBundles", bundles));
    return new ClusterEnvironmentIdeConfiguration()
        .clusterInternalHttpClient(properties, beanFactory.getBeanProvider(SslBundles.class));
  }

  /** 受 bundle 信任的用例客户端。 */
  private static HttpClient trustedClusterClient() {
    return track(clusterInternalHttpClient(sslBundles));
  }

  /** 只用 JVM 默认信任的用例客户端。 */
  private static HttpClient systemDefaultTrustClient() {
    return track(HttpClient.newHttpClient());
  }

  /** 用例客户端统一在 {@link #stopServers()} 关闭，不把连接与共享线程留在测试进程里。 */
  private static HttpClient track(HttpClient client) {
    tlsClients.add(client);
    return client;
  }

  /** 把常驻网关指向目标 owner；{@code host} 决定主机身份是否被证书覆盖（IP 字面量不发送 SNI）。 */
  private static void forwardTo(HttpClient client, String host) {
    forwardingTarget.set(
        new ForwardingTarget(
            new IdeRemoteDataPlaneForwarder(client, clusterClient),
            ClusterOwnerRoute.remote(
                UUID.randomUUID(), URI.create("https://" + host + ":" + tlsOwnerPort))));
  }

  /** owner 侧隧道：读完请求正文后按 200 回一段 JSON；用来区分“TLS 未建立”与“已经送达 owner”。 */
  private static void setHttpTunnel(AtomicReference<String> receivedBody, String responsePayload) {
    ownerTunnels.setTunnel(
        new SimpleEnvironmentTunnel(
            IDENTITY,
            req -> {
              SimpleTunnelExchange exchange = new SimpleTunnelExchange(req);
              Thread.ofVirtual()
                  .start(
                      () -> {
                        try {
                          ByteArrayOutputStream body = new ByteArrayOutputStream();
                          while (true) {
                            ByteBuffer chunk =
                                exchange
                                    .peer()
                                    .read()
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS);
                            if (chunk == null) {
                              break;
                            }
                            byte[] bytes = new byte[chunk.remaining()];
                            chunk.get(bytes);
                            body.write(bytes);
                          }
                          receivedBody.set(body.toString(StandardCharsets.UTF_8));
                          exchange.respond(
                              new TunnelExchange.Response(
                                  200, Map.of("Content-Type", List.of("application/json"))));
                          exchange
                              .peer()
                              .write(
                                  ByteBuffer.wrap(responsePayload.getBytes(StandardCharsets.UTF_8)))
                              .toCompletableFuture()
                              .get(5, TimeUnit.SECONDS);
                          exchange
                              .peer()
                              .endOutput()
                              .toCompletableFuture()
                              .get(5, TimeUnit.SECONDS);
                        } catch (Exception error) {
                          exchange.close();
                        }
                      });
              return exchange;
            },
            req -> {
              throw new UnsupportedOperationException();
            }));
  }

  /** owner 侧 WebSocket 隧道：完成 200 握手并在客户端帧到达后回一帧；{@code reached} 记录握手是否真的到达 owner。 */
  private static void setWebSocketEchoTunnel(AtomicBoolean reached, String replyText) {
    ownerTunnels.setTunnel(
        new SimpleEnvironmentTunnel(
            IDENTITY,
            req -> {
              throw new UnsupportedOperationException();
            },
            req -> {
              SimpleTunnelExchange exchange = new SimpleTunnelExchange(req);
              String accept = computeAcceptHeader(req);
              Thread.ofVirtual()
                  .start(
                      () -> {
                        try {
                          reached.set(true);
                          exchange.respond(
                              new TunnelExchange.Response(
                                  200, Map.of("Sec-WebSocket-Accept", List.of(accept))));
                          ByteBuffer frame =
                              exchange
                                  .peer()
                                  .read()
                                  .toCompletableFuture()
                                  .get(10, TimeUnit.SECONDS);
                          if (frame != null) {
                            exchange
                                .peer()
                                .write(serverTextFrame(replyText))
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS);
                          }
                        } catch (Exception error) {
                          exchange.close();
                        }
                      });
              return exchange;
            }));
  }

  private static HttpResponse<String> sendTlsHttpRequest() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + tlsGatewayPort
                        + "/tls-ide/"
                        + ENV
                        + "/"
                        + VIEW
                        + "/data/save"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hello over tls\"}"))
            .header("Cookie", IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie)
            .header("Content-Type", "application/json")
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void tlsInternalHttpForwardingWithTrustedBundle() throws Exception {
    AtomicReference<String> receivedBody = new AtomicReference<>();
    setHttpTunnel(receivedBody, "{\"status\":\"saved\"}");
    forwardTo(trustedClusterClient(), TLS_HOST);

    HttpResponse<String> response = sendTlsHttpRequest();

    assertEquals(200, response.statusCode());
    assertEquals("{\"status\":\"saved\"}", response.body());
    assertEquals("{\"message\":\"hello over tls\"}", receivedBody.get());
  }

  @Test
  void tlsWebSocketForwardingWithTrustedBundle() throws Exception {
    AtomicBoolean ownerReached = new AtomicBoolean();
    setWebSocketEchoTunnel(ownerReached, "tls-echo");
    forwardTo(trustedClusterClient(), TLS_HOST);

    TestFrameHandler clientHandler = new TestFrameHandler();
    URI wsUri =
        URI.create(
            "ws://127.0.0.1:" + tlsGatewayPort + "/tls-ide/" + ENV + "/" + VIEW + "/ws-terminal");
    CoreClientUpgradeRequest upgradeRequest =
        CoreClientUpgradeRequest.from(wsClient, wsUri, clientHandler);
    upgradeRequest.headers(
        h -> h.add("Cookie", IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie));
    CoreSession session = upgradeRequest.sendAsync().get(10, TimeUnit.SECONDS);
    session.sendFrame(new Frame(OpCode.TEXT, "tls-hello"), Callback.NOOP, false);
    Frame echo = clientHandler.nextFrame(10, TimeUnit.SECONDS);

    assertNotNull(echo, "owner 的 WebSocket 帧必须经 TLS 转发回浏览器");
    assertEquals(OpCode.TEXT, echo.getOpCode());
    assertEquals("tls-echo", echo.getPayloadAsUTF8());
    assertTrue(ownerReached.get(), "浏览器帧必须经 TLS 送达 owner");
    session.close(1000, "normal", Callback.NOOP);
  }

  @Test
  void tlsInternalHttpForwardingRejectsHostNameMismatch() throws Exception {
    AtomicReference<String> receivedBody = new AtomicReference<>();
    setHttpTunnel(receivedBody, "{\"status\":\"saved\"}");
    // 证书链受信，但不覆盖 127.0.0.1（且 IP 字面量不发送 SNI）：主机身份校验必须失败。
    forwardTo(trustedClusterClient(), "127.0.0.1");

    assertEquals(503, sendTlsHttpRequest().statusCode());
    assertNull(receivedBody.get(), "主机名不匹配时请求不得送达 owner");
  }

  @Test
  void tlsWebSocketForwardingRejectsHostNameMismatch() throws Exception {
    AtomicBoolean ownerReached = new AtomicBoolean();
    setWebSocketEchoTunnel(ownerReached, "tls-echo");
    forwardTo(trustedClusterClient(), "127.0.0.1");

    assertEquals(
        502,
        sendRawWebSocketUpgrade(
            tlsGatewayPort,
            "/tls-ide/" + ENV + "/" + VIEW + "/ws-terminal",
            IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + sessionCookie));
    assertFalse(ownerReached.get(), "主机名不匹配时握手不得送达 owner");
  }

  @Test
  void tlsInternalHttpForwardingRejectsUntrustedCertificate() throws Exception {
    AtomicReference<String> receivedBody = new AtomicReference<>();
    setHttpTunnel(receivedBody, "{\"status\":\"saved\"}");
    forwardTo(systemDefaultTrustClient(), TLS_HOST);

    assertEquals(503, sendTlsHttpRequest().statusCode());
    assertNull(receivedBody.get(), "证书不受信时请求不得送达 owner");
  }

  /** 运行时生成一张只覆盖 {@code localhost} 的自签证书；私钥只落在临时目录，不提交。 */
  private static void generateSelfSignedCertificate(Path keyStore) throws Exception {
    keytool(
        "-genkeypair",
        "-alias",
        "cluster",
        "-keyalg",
        "RSA",
        "-dname",
        "CN=localhost,OU=kk-studio-test",
        "-ext",
        "SAN=dns:localhost",
        "-validity",
        "2",
        "-storetype",
        "PKCS12",
        "-keystore",
        keyStore.toString(),
        "-storepass",
        KEY_STORE_PASSWORD,
        "-keypass",
        KEY_STORE_PASSWORD);
  }

  private static void keytool(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("keytool timed out: " + String.join(" ", command));
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      fail("keytool failed: " + String.join(" ", command) + System.lineSeparator() + output);
    }
  }

  /** 只把自签公钥证书从 PKCS12 复制进信任库（私钥不进入 bundle）。 */
  private static KeyStore trustStore(Path keyStore) throws Exception {
    KeyStore keyMaterial = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(keyStore)) {
      keyMaterial.load(in, KEY_STORE_PASSWORD.toCharArray());
    }
    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("cluster", keyMaterial.getCertificate("cluster"));
    return trustStore;
  }

  /** 常驻 TLS 网关的转发目标：每个用例只替换它，不再新起 Jetty。 */
  private record ForwardingTarget(IdeRemoteDataPlaneForwarder forwarder, ClusterOwnerRoute owner) {}

  private static class TlsForwardingServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      ForwardingTarget target = forwardingTarget.get();
      String prefix = "/tls-ide/" + ENV + "/" + VIEW;
      String uri = request.getRequestURI();
      if (target == null || !uri.startsWith(prefix)) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        return;
      }
      String rawPathAndQuery = uri.substring(prefix.length());
      String query = request.getQueryString();
      if (query != null && !query.isEmpty()) {
        rawPathAndQuery = rawPathAndQuery + "?" + query;
      }
      if (StudioOriginPolicy.isWebSocketUpgrade(request)) {
        target
            .forwarder()
            .forwardWebSocket(request, response, target.owner(), ENV, VIEW, rawPathAndQuery);
      } else {
        target
            .forwarder()
            .forwardDataHttp(request, response, target.owner(), ENV, VIEW, rawPathAndQuery);
      }
    }
  }

  // --- Supporting classes ---

  private static class GatewayServlet extends HttpServlet {
    private final IdeGatewayDataPlane dataPlane;

    GatewayServlet(IdeGatewayDataPlane dataPlane) {
      this.dataPlane = dataPlane;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      req.setAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, PRINCIPAL);
      String uri = req.getRequestURI();
      String prefix = "/ide/";
      if (!uri.startsWith(prefix)) {
        resp.sendError(404);
        return;
      }
      String sub = uri.substring(prefix.length());
      int firstSlash = sub.indexOf('/');
      if (firstSlash < 0) {
        resp.sendError(404);
        return;
      }
      EnvironmentId envId = EnvironmentId.parse(sub.substring(0, firstSlash));
      String afterEnv = sub.substring(firstSlash + 1);
      int secondSlash = afterEnv.indexOf('/');
      String viewIdStr = secondSlash < 0 ? afterEnv : afterEnv.substring(0, secondSlash);
      UUID viewId = UUID.fromString(viewIdStr);
      String viewPath = secondSlash < 0 ? "/" : afterEnv.substring(secondSlash);
      String query = req.getQueryString();
      String rawPathAndQuery =
          query != null && !query.isEmpty() ? viewPath + "?" + query : viewPath;
      dataPlane.handleBrowserTraffic(envId, viewId, rawPathAndQuery, req, resp);
    }
  }

  private static class InternalServlet extends HttpServlet {
    private final IdeGatewayDataPlane dataPlane;

    InternalServlet(IdeGatewayDataPlane dataPlane) {
      this.dataPlane = dataPlane;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      String uri = req.getRequestURI();
      String prefix = "/internal/ide/environments/";
      if (!uri.startsWith(prefix)) {
        resp.sendError(404);
        return;
      }
      String sub = uri.substring(prefix.length());
      int slash = sub.indexOf('/');
      if (slash < 0) {
        resp.sendError(404);
        return;
      }
      EnvironmentId envId = EnvironmentId.parse(sub.substring(0, slash));
      String viewIdStr = req.getParameter(IdeDataPaths.VIEW_ID_PARAMETER);
      String rawPathAndQuery = req.getParameter(IdeDataPaths.PATH_PARAMETER);
      if (viewIdStr == null || rawPathAndQuery == null) {
        resp.sendError(400);
        return;
      }
      UUID viewId = UUID.fromString(viewIdStr);
      HttpServletRequest forwarded = IdeForwardedRequest.of(req, rawPathAndQuery);
      dataPlane.handleInternalDataPlane(envId, viewId, rawPathAndQuery, forwarded, resp);
    }
  }

  private static class TestEnvironmentTunnels implements EnvironmentTunnels {
    private volatile EnvironmentTunnel tunnel;

    void setTunnel(EnvironmentTunnel tunnel) {
      this.tunnel = tunnel;
    }

    @Override
    public Optional<EnvironmentTunnel> local(EnvironmentId environmentId) {
      return Optional.ofNullable(tunnel);
    }
  }

  private static class SimpleEnvironmentTunnel implements EnvironmentTunnel {
    private final DaemonConnectionIdentity identity;
    private final Function<TunnelHttpRequest, TunnelExchange> onHttp;
    private final Function<TunnelHttpRequest, TunnelExchange> onWebSocket;

    SimpleEnvironmentTunnel(
        DaemonConnectionIdentity identity,
        Function<TunnelHttpRequest, TunnelExchange> onHttp,
        Function<TunnelHttpRequest, TunnelExchange> onWebSocket) {
      this.identity = identity;
      this.onHttp = onHttp;
      this.onWebSocket = onWebSocket;
    }

    @Override
    public DaemonConnectionIdentity identity() {
      return identity;
    }

    @Override
    public TunnelExchange openHttp(TunnelHttpRequest request) {
      return onHttp.apply(request);
    }

    @Override
    public TunnelExchange openWebSocket(TunnelHttpRequest request) {
      return onWebSocket.apply(request);
    }
  }

  private static class SimpleTunnelExchange implements TunnelExchange {
    private final CompletableFuture<Response> responseFuture = new CompletableFuture<>();
    private final SimpleTunnelStream stream = new SimpleTunnelStream();
    private volatile TunnelStream customStream;

    SimpleTunnelExchange(TunnelHttpRequest request) {}

    void setCustomStream(TunnelStream stream) {
      this.customStream = stream;
    }

    @Override
    public CompletionStage<Response> responseHeaders() {
      return responseFuture;
    }

    @Override
    public TunnelStream stream() {
      return customStream != null ? customStream : stream;
    }

    public SimpleTunnelStream.Peer peer() {
      return stream.peer();
    }

    public void respond(Response response) {
      responseFuture.complete(response);
    }

    @Override
    public void close() {
      if (customStream != null) {
        customStream.close();
      } else {
        stream.close();
      }
    }
  }

  private static final ByteBuffer EOF_SENTINEL = ByteBuffer.allocate(0);

  private static class SimpleTunnelStream implements TunnelStream {
    private final BlockingQueue<ByteBuffer> toApp = new LinkedBlockingQueue<>();
    private final BlockingQueue<ByteBuffer> toPeer = new LinkedBlockingQueue<>();
    private final AtomicBoolean appOutputEnded = new AtomicBoolean();
    private final AtomicBoolean peerOutputEnded = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public CompletionStage<ByteBuffer> read() {
      if (closed.get()) {
        return CompletableFuture.completedFuture(null);
      }
      return CompletableFuture.supplyAsync(
          () -> {
            try {
              ByteBuffer b = toApp.take();
              return b == EOF_SENTINEL ? null : b;
            } catch (InterruptedException e) {
              return null;
            }
          });
    }

    @Override
    public CompletionStage<Void> write(ByteBuffer source) {
      if (closed.get() || appOutputEnded.get()) {
        return CompletableFuture.failedFuture(new IOException("stream closed or ended"));
      }
      ByteBuffer copy = ByteBuffer.allocate(source.remaining());
      copy.put(source);
      copy.flip();
      toPeer.add(copy);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> endOutput() {
      if (appOutputEnded.compareAndSet(false, true)) {
        toPeer.add(EOF_SENTINEL);
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        toApp.add(EOF_SENTINEL);
        toPeer.add(EOF_SENTINEL);
      }
    }

    public Peer peer() {
      return new Peer();
    }

    class Peer {
      public CompletionStage<ByteBuffer> read() {
        if (closed.get()) {
          return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(
            () -> {
              try {
                ByteBuffer b = toPeer.take();
                return b == EOF_SENTINEL ? null : b;
              } catch (InterruptedException e) {
                return null;
              }
            });
      }

      public CompletionStage<Void> write(ByteBuffer source) {
        if (closed.get() || peerOutputEnded.get()) {
          return CompletableFuture.failedFuture(new IOException("peer closed or ended"));
        }
        ByteBuffer copy = ByteBuffer.allocate(source.remaining());
        copy.put(source);
        copy.flip();
        toApp.add(copy);
        return CompletableFuture.completedFuture(null);
      }

      public CompletionStage<Void> endOutput() {
        if (peerOutputEnded.compareAndSet(false, true)) {
          toApp.add(EOF_SENTINEL);
        }
        return CompletableFuture.completedFuture(null);
      }
    }
  }

  private static class TestFrameHandler implements FrameHandler {
    private CoreSession session;
    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<Boolean> closedFuture = new CompletableFuture<>();

    @Override
    public void onOpen(CoreSession session, Callback callback) {
      this.session = session;
      callback.succeeded();
      session.demand();
    }

    @Override
    public void onFrame(Frame frame, Callback callback) {
      frames.add(Frame.copy(frame));
      callback.succeeded();
      if (session != null) {
        session.demand();
      }
    }

    @Override
    public void onError(Throwable cause, Callback callback) {
      callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback) {
      closedFuture.complete(true);
      callback.succeeded();
    }

    public Frame nextFrame(long timeout, TimeUnit unit) throws InterruptedException {
      return frames.poll(timeout, unit);
    }
  }
}
