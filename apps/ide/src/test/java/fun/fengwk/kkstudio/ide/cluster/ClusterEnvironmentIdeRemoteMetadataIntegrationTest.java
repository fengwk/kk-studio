package fun.fengwk.kkstudio.ide.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeBrowserUseGateway;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.IdeViewAuthorizationListener;
import fun.fengwk.kkstudio.ide.IdeViewBindingRegistry;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeDirectoryListing;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 意图：真实 HTTP 一跳验证 A→B 元数据路由与 HMAC 认证——A 只发一次已签名内部请求，B 校验认证后由 owner 本地执行，返回的 DTO 携带 B 铸造的 view 级
 * bootstrap 入口；带文档命令在 wire 上携带 document+epoch。路由失效、认证失败和响应超界都在交付前 fail-closed。
 */
class ClusterEnvironmentIdeRemoteMetadataIntegrationTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String DOCUMENT = "66666666-7777-8888-9999-aaaaaaaaaaaa";
  private static final String EVENT_NODE = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
  private static final String CONNECTION = "12345678-1234-1234-1234-123456789abc";
  private static final String CLAIM = "87654321-4321-4321-4321-cba987654321";
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";

  private static final List<String> AUTH_HEADER_NAMES =
      List.of(
          HmacClusterInternalAuthentication.HEADER_TARGET,
          HmacClusterInternalAuthentication.HEADER_TIMESTAMP,
          HmacClusterInternalAuthentication.HEADER_NONCE,
          HmacClusterInternalAuthentication.HEADER_SIGNATURE);

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final StubRegistry registryA = new StubRegistry();
  private final RecordingTransport localTransportA = new RecordingTransport();

  private HttpServer server;
  private HmacClusterInternalAuthentication nodeBAuthentication;
  private EnvironmentIdeServiceImpl nodeBLocal;
  private RecordingTransport transportB;
  private AtomicInteger received;
  private ClusterEnvironmentIdeService nodeAService;

  @BeforeEach
  void setUp() throws IOException {
    received = new AtomicInteger();
    nodeBAuthentication =
        new HmacClusterInternalAuthentication(
            SECRET, NODE_B, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    transportB = new RecordingTransport();
    nodeBLocal =
        new EnvironmentIdeServiceImpl(
            transportB,
            new IdeBootstrapTicketService(),
            new AllowGateway(),
            new IdeViewBindingRegistry(),
            Duration.ofSeconds(1),
            new NoopAuthorization());

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/ide/environments/", this::handle);
    server.start();
    int port = server.getAddress().getPort();
    registryA.current =
        Optional.of(ClusterOwnerRoute.remote(NODE_B, URI.create("http://127.0.0.1:" + port)));
    nodeAService = serviceWithSecret(SECRET);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void remoteOpenViewIsOneHopAndTheOwnerIssuesTheViewScopedBootstrapEntry() {
    EnvironmentIdeViewDTO dto = nodeAService.openView(ENV, openViewRequest("/home/dev/project"));

    assertEquals(VIEW, dto.getViewId());
    assertEquals(ENV.toString(), dto.getEnvironmentId());
    assertEquals(7L, dto.getBindingEpoch());
    assertEquals("/home/dev/project", dto.getDirectory());
    assertEquals("/daemon/workspaces/" + VIEW, dto.getWorkspacePath());
    assertTrue(
        dto.getEntryUrl().startsWith("/ide/" + ENV + "/" + VIEW + "/__ide/bootstrap?token="),
        () -> "unexpected entry url: " + dto.getEntryUrl());
    assertEquals(1, received.get());
    assertTrue(localTransportA.commands.isEmpty());
    assertEquals(IdeAction.OPEN_VIEW, transportB.commands.getFirst().getAction());
  }

  @Test
  void remoteBoundCommandsCarryDocumentAndEpochOverAuthenticatedHttp() {
    nodeAService.openView(ENV, openViewRequest("/home/dev/project"));
    EnvironmentIdeViewBindingDTO binding = binding(7L);

    nodeAService.focusTerminal(ENV, VIEW, binding);

    assertEquals(2, received.get());
    assertEquals(2, transportB.commands.size());
    IdeCommand focus = transportB.commands.getLast();
    assertEquals(IdeAction.FOCUS_TERMINAL, focus.getAction());
    assertEquals(DOCUMENT.toString(), focus.getDocumentId());
    assertEquals(7L, focus.getBindingEpoch());
    assertTrue(localTransportA.commands.isEmpty());
  }

  /** 编码查询参与签名，远端按解码后的原始路径发送命令。 */
  @Test
  void directoryQueryAndMobileCommandReachRemoteOwner() {
    assertEquals("/", nodeAService.listDirectories(ENV, "/a b+中").getPath());
    assertEquals("/a b+中", transportB.commands.getLast().getDirectory());
    nodeAService.openView(ENV, openViewRequest(null));
    nodeAService.collapseMobile(ENV, VIEW, binding(7L));
    assertEquals(IdeAction.COLLAPSE_MOBILE, transportB.commands.getLast().getAction());
    assertEquals(3, received.get());
    assertThrows(DomainException.class, () -> nodeAService.listDirectories(ENV, "x".repeat(4097)));
    assertEquals(3, received.get());
  }

  @Test
  void unavailableRouteFailsClosedWithoutContactingTheRemoteOwner() {
    registryA.current = Optional.empty();

    assertThrows(DomainException.class, () -> nodeAService.openView(ENV, openViewRequest(null)));

    assertEquals(0, received.get());
    assertTrue(localTransportA.commands.isEmpty());
  }

  @Test
  void invalidSignatureIsRejectedBeforeTheOwnerExecutes() {
    ClusterEnvironmentIdeService bad = serviceWithSecret("wrong-cluster-secret-0123456789abcdef");

    assertThrows(DomainException.class, () -> bad.openView(ENV, openViewRequest(null)));

    assertEquals(1, received.get());
    assertTrue(transportB.commands.isEmpty());
  }

  @Test
  void oversizedResponseBodyIsRejectedByTheTotalByteCap() throws IOException {
    HttpServer oversizeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    oversizeServer.createContext(
        "/internal/ide/environments/",
        exchange -> {
          String body =
              "{\"viewId\":\""
                  + VIEW
                  + "\",\"environmentId\":\""
                  + ENV
                  + "\",\"directory\":\""
                  + "a".repeat(4096)
                  + "\"}";
          respond(exchange, 200, body);
        });
    oversizeServer.start();
    try {
      IdeClusterClient smallClient =
          new IdeClusterClient(
              HttpClient.newHttpClient(),
              objectMapper,
              new HmacClusterInternalAuthentication(
                  SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024),
              Duration.ofSeconds(5),
              1024);
      StubRegistry registry = new StubRegistry();
      registry.current =
          Optional.of(
              ClusterOwnerRoute.remote(
                  NODE_B, URI.create("http://127.0.0.1:" + oversizeServer.getAddress().getPort())));
      ClusterEnvironmentIdeService service =
          new ClusterEnvironmentIdeService(registry, smallClient, localService());

      assertThrows(DomainException.class, () -> service.openView(ENV, openViewRequest(null)));
    } finally {
      oversizeServer.stop(0);
    }
  }

  /** 普通关闭的响应头和响应体都可等待确认。 */
  @Test
  void normalRemoteCloseWaitsForHeadersAndBody() throws Exception {
    CountDownLatch receivedRequest = new CountDownLatch(1);
    CountDownLatch sendHeaders = new CountDownLatch(1);
    CountDownLatch headersSent = new CountDownLatch(1);
    CountDownLatch finishBody = new CountDownLatch(1);
    HttpServer delayed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    delayed.createContext(
        "/internal/ide/environments/",
        exchange -> {
          receivedRequest.countDown();
          try {
            sendHeaders.await(5, TimeUnit.SECONDS);
            exchange.sendResponseHeaders(200, 0);
            headersSent.countDown();
            finishBody.await(5, TimeUnit.SECONDS);
            exchange.close();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            exchange.close();
          }
        });
    delayed.start();
    try {
      IdeClusterClient client =
          new IdeClusterClient(
              HttpClient.newHttpClient(),
              objectMapper,
              new HmacClusterInternalAuthentication(
                  SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024),
              Duration.ofMillis(200),
              1024);
      ClusterOwnerRoute owner =
          ClusterOwnerRoute.remote(
              NODE_B, URI.create("http://127.0.0.1:" + delayed.getAddress().getPort()));
      EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
      close.setDiscard(false);
      CompletableFuture<Void> waiting =
          CompletableFuture.runAsync(() -> client.closeView(ENV, owner, VIEW, close));
      assertTrue(receivedRequest.await(2, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> waiting.get(450, TimeUnit.MILLISECONDS));
      sendHeaders.countDown();
      assertTrue(headersSent.await(2, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> waiting.get(450, TimeUnit.MILLISECONDS));
      finishBody.countDown();
      waiting.get(2, TimeUnit.SECONDS);
    } finally {
      sendHeaders.countDown();
      finishBody.countDown();
      delayed.stop(0);
    }
  }

  /** 等待时间无限不意味着响应体大小无限。 */
  @Test
  void normalRemoteCloseRejectsOversizedBody() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/ide/environments/", exchange -> respond(exchange, 200, "x".repeat(2048)));
    server.start();
    try {
      IdeClusterClient client =
          new IdeClusterClient(
              HttpClient.newHttpClient(),
              objectMapper,
              new HmacClusterInternalAuthentication(
                  SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024),
              Duration.ofMillis(200),
              1024);
      ClusterOwnerRoute owner =
          ClusterOwnerRoute.remote(
              NODE_B, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
      close.setDiscard(false);
      assertThrows(DomainException.class, () -> client.closeView(ENV, owner, VIEW, close));
    } finally {
      server.stop(0);
    }
  }

  /** discard 关闭保持超时限制，不进行无限等待。 */
  @Test
  void remoteDiscardCloseTimesOut() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    HttpServer delayed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    delayed.createContext(
        "/internal/ide/environments/",
        exchange -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    delayed.start();
    try {
      IdeClusterClient client =
          new IdeClusterClient(
              HttpClient.newHttpClient(),
              objectMapper,
              new HmacClusterInternalAuthentication(
                  SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024),
              Duration.ofMillis(200),
              1024);
      ClusterOwnerRoute owner =
          ClusterOwnerRoute.remote(
              NODE_B, URI.create("http://127.0.0.1:" + delayed.getAddress().getPort()));
      EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
      close.setDiscard(true);
      assertThrows(DomainException.class, () -> client.closeView(ENV, owner, VIEW, close));
    } finally {
      release.countDown();
      delayed.stop(0);
    }
  }

  @Test
  void stalledResponseBodyHitsTheClientDeadlineInsteadOfHanging() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    HttpServer stalledServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stalledServer.createContext(
        "/internal/ide/environments/",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write("{\"viewId\":\"".getBytes(StandardCharsets.UTF_8));
            out.flush();
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    stalledServer.start();
    try {
      IdeClusterClient shortDeadlineClient =
          new IdeClusterClient(
              HttpClient.newHttpClient(),
              objectMapper,
              new HmacClusterInternalAuthentication(
                  SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024),
              Duration.ofMillis(300),
              64 * 1024);
      StubRegistry registry = new StubRegistry();
      registry.current =
          Optional.of(
              ClusterOwnerRoute.remote(
                  NODE_B, URI.create("http://127.0.0.1:" + stalledServer.getAddress().getPort())));
      ClusterEnvironmentIdeService service =
          new ClusterEnvironmentIdeService(registry, shortDeadlineClient, localService());

      long start = System.nanoTime();
      assertThrows(DomainException.class, () -> service.openView(ENV, openViewRequest(null)));
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      assertTrue(
          elapsedMillis < 2_000,
          () -> "client must not hang on a stalled response body, took " + elapsedMillis + "ms");
    } finally {
      release.countDown();
      stalledServer.stop(0);
    }
  }

  @Test
  void oversizedDirectoryIsRejectedBeforeAnyNetworkCall() {
    EnvironmentIdeOpenViewDTO request = openViewRequest(null);
    request.setDirectory("x".repeat(20 * 1024));

    assertThrows(DomainException.class, () -> nodeAService.openView(ENV, request));
    assertEquals(0, received.get());
  }

  private ClusterEnvironmentIdeService serviceWithSecret(String secret) {
    IdeClusterClient client =
        new IdeClusterClient(
            HttpClient.newHttpClient(),
            objectMapper,
            new HmacClusterInternalAuthentication(
                secret, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024));
    return new ClusterEnvironmentIdeService(registryA, client, localService());
  }

  private EnvironmentIdeServiceImpl localService() {
    return new EnvironmentIdeServiceImpl(
        localTransportA,
        new IdeBootstrapTicketService(),
        new AllowGateway(),
        new IdeViewBindingRegistry(),
        Duration.ofSeconds(1),
        new NoopAuthorization());
  }

  private void handle(HttpExchange exchange) throws IOException {
    received.incrementAndGet();
    String path = exchange.getRequestURI().getPath();
    Map<String, String> headers = new HashMap<>();
    for (String name : AUTH_HEADER_NAMES) {
      String value = exchange.getRequestHeaders().getFirst(name);
      if (value != null) {
        headers.put(name, value);
      }
    }
    try {
      nodeBAuthentication.verify(
          exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers);
    } catch (ClusterInternalAuthenticationException error) {
      respond(exchange, 401, "{\"message\":\"internal authentication failed\"}");
      return;
    }
    EnvironmentId environmentId = environmentIdFrom(path);
    String method = exchange.getRequestMethod();
    String[] segments = path.split("/");
    try {
      if ("GET".equals(method) && path.endsWith("/directories")) {
        String query = exchange.getRequestURI().getRawQuery();
        String directory =
            query == null ? null : URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8);
        respond(
            exchange,
            200,
            objectMapper.writeValueAsString(nodeBLocal.listDirectories(environmentId, directory)));
        return;
      }
      if ("POST".equals(method) && path.endsWith("/views")) {
        EnvironmentIdeOpenViewDTO request =
            objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeOpenViewDTO.class);
        respond(
            exchange,
            200,
            objectMapper.writeValueAsString(nodeBLocal.openView(environmentId, request)));
        return;
      }
      if ("GET".equals(method) && path.endsWith("/views")) {
        EnvironmentIdeViewsDTO views = nodeBLocal.listViews(environmentId);
        respond(exchange, 200, objectMapper.writeValueAsString(views));
        return;
      }
      if ("PUT".equals(method) && segments.length == 8 && "directory".equals(segments[7])) {
        EnvironmentIdeChangeDirectoryDTO request =
            objectMapper.readValue(
                exchange.getRequestBody(), EnvironmentIdeChangeDirectoryDTO.class);
        respond(
            exchange,
            200,
            objectMapper.writeValueAsString(
                nodeBLocal.changeDirectory(environmentId, segments[6], request)));
        return;
      }
      if ("POST".equals(method) && segments.length == 8 && "focus-terminal".equals(segments[7])) {
        EnvironmentIdeViewBindingDTO request =
            objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeViewBindingDTO.class);
        nodeBLocal.focusTerminal(environmentId, segments[6], request);
        respond(exchange, 204, "");
        return;
      }
      if ("POST".equals(method)
          && segments.length == 8
          && "apply-mobile-layout".equals(segments[7])) {
        EnvironmentIdeViewBindingDTO request =
            objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeViewBindingDTO.class);
        nodeBLocal.collapseMobile(environmentId, segments[6], request);
        respond(exchange, 204, "");
        return;
      }
      if ("POST".equals(method) && segments.length == 8 && "close".equals(segments[7])) {
        EnvironmentIdeCloseViewDTO request =
            objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeCloseViewDTO.class);
        nodeBLocal.closeView(environmentId, segments[6], request);
        respond(exchange, 204, "");
        return;
      }
      respond(exchange, 404, "{\"message\":\"unsupported internal IDE command\"}");
    } catch (IllegalArgumentException | IllegalStateException error) {
      respond(exchange, 400, "{\"message\":\"invalid internal IDE request\"}");
    }
  }

  private static EnvironmentId environmentIdFrom(String path) {
    String[] segments = path.split("/");
    return EnvironmentId.parse(segments[4]);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  private static EnvironmentIdeOpenViewDTO openViewRequest(String directory) {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setDocumentId(DOCUMENT);
    request.setEventNodeId(EVENT_NODE);
    request.setConnectionId(CONNECTION);
    request.setClaimId(CLAIM);
    request.setMode(EnvironmentIdeOpenMode.CREATE);
    request.setDirectory(directory);
    return request;
  }

  private static EnvironmentIdeViewBindingDTO binding(long epoch) {
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    binding.setDocumentId(DOCUMENT);
    binding.setBindingEpoch(epoch);
    return binding;
  }

  private static final class StubRegistry implements ClusterNodeRegistry {

    private Optional<ClusterOwnerRoute> current = Optional.empty();

    @Override
    public Optional<ClusterOwnerRoute> route(EnvironmentId environmentId) {
      return current;
    }

    @Override
    public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
      return Optional.empty();
    }
  }

  private static final class RecordingTransport extends IdeCommandTransport {

    private RecordingTransport() {
      super(mock(EnvironmentDaemonServer.class));
    }

    private final List<IdeCommand> commands = new ArrayList<>();

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeResult> reply = new CompletableFuture<>();
      reply.complete(replyFor(command));
      return new IdeRequest(
          new DaemonConnectionIdentity(environmentId, "daemon-instance"), UUID.randomUUID(), reply);
    }

    private static IdeResult replyFor(IdeCommand command) {
      if (command.getAction() == IdeAction.LIST_DIRECTORIES) {
        return IdeResult.newBuilder()
            .setOk(true)
            .setDirectoryListing(IdeDirectoryListing.newBuilder().setPath("/").setTruncated(false))
            .build();
      }
      if (command.getAction() == IdeAction.LIST_VIEWS) {
        return IdeResult.newBuilder().setOk(true).build();
      }
      String directory =
          command.getAction() == IdeAction.CHANGE_DIRECTORY
              ? command.getDirectory()
              : "/home/dev/project";
      IdeResult.Builder builder =
          IdeResult.newBuilder()
              .setOk(true)
              .setViewId(command.getViewId())
              .setBindingEpoch(7L)
              .setDirectory(directory)
              .setWorkspacePath("/daemon/workspaces/" + command.getViewId());
      if (command.hasDocumentId()) {
        builder.setDocumentId(command.getDocumentId());
      }
      if (command.hasEventNodeId()) {
        builder.setEventNodeId(command.getEventNodeId());
      }
      if (command.hasConnectionId()) {
        builder.setConnectionId(command.getConnectionId());
      }
      if (command.hasClaimId()) {
        builder.setClaimId(command.getClaimId());
      }
      return builder.build();
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(new DaemonConnectionIdentity(environmentId, "daemon-instance"));
    }

    @Override
    public void sendIdeLease(
        EnvironmentId environmentId,
        DaemonConnectionIdentity expectedIdentity,
        DaemonIdeLease lease) {
      throw new AssertionError("unexpected lease");
    }

    @Override
    public void abandonIde(UUID invocationId) {}
  }

  private static final class AllowGateway extends IdeBrowserUseGateway {

    private AllowGateway() {
      super(mock(IdeBrowserUseClient.class));
    }

    @Override
    public boolean findExactIntent(
        UUID eventNodeId,
        UUID connectionId,
        UUID documentId,
        EnvironmentId environmentId,
        UUID viewId,
        UUID claimId) {
      return true;
    }

    @Override
    public Optional<Proofs> activeBoundProofs(
        UUID eventNodeId, UUID connectionId, UUID documentId) {
      return Optional.empty();
    }
  }

  private static final class NoopAuthorization implements IdeViewAuthorizationListener {

    @Override
    public void ensureViewCapacity(EnvironmentId environmentId) {}

    @Override
    public void viewOpened(
        DaemonConnectionIdentity identity,
        String viewId,
        long bindingEpoch,
        String workspacePath) {}

    @Override
    public void viewClosed(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {}
  }
}
