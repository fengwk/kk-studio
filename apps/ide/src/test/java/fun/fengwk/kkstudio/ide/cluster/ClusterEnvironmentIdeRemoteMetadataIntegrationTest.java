package fun.fengwk.kkstudio.ide.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBrowserUseGateway;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.IdeViewAuthorizationListener;
import fun.fengwk.kkstudio.ide.IdeViewBindingRegistry;
import fun.fengwk.kkstudio.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
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
            new InMemoryIdeBootstrapTicketService(),
            new AllowGateway(),
            new IdeViewBindingRegistry(),
            Duration.ofSeconds(1),
            new NoopAuthorization());

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/environment-ide/", this::handle);
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
    assertEquals("7", dto.getBindingEpoch());
    assertEquals("/home/dev/project", dto.getDirectory());
    assertEquals("/daemon/workspaces/" + VIEW, dto.getWorkspacePath());
    assertTrue(
        dto.getEntryUrl()
            .startsWith("/environment-ide/" + ENV + "/" + VIEW + "/__ide/bootstrap?token="),
        () -> "unexpected entry url: " + dto.getEntryUrl());
    assertEquals(1, received.get());
    assertTrue(localTransportA.commands.isEmpty());
    assertEquals(IdeAction.OPEN_VIEW, transportB.commands.getFirst().action());
  }

  @Test
  void remoteBoundCommandsCarryDocumentAndEpochOverAuthenticatedHttp() {
    nodeAService.openView(ENV, openViewRequest("/home/dev/project"));
    EnvironmentIdeViewBindingDTO binding = binding("7");

    nodeAService.focusTerminal(ENV, VIEW, binding);

    assertEquals(2, received.get());
    assertEquals(2, transportB.commands.size());
    IdeCommand focus = transportB.commands.getLast();
    assertEquals(IdeAction.FOCUS_TERMINAL, focus.action());
    assertEquals(DOCUMENT, focus.documentId());
    assertEquals(7L, focus.bindingEpoch());
    assertTrue(localTransportA.commands.isEmpty());
  }

  @Test
  void unavailableRouteFailsClosedWithoutContactingTheRemoteOwner() {
    registryA.current = Optional.empty();

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> nodeAService.openView(ENV, openViewRequest(null)));

    assertEquals(0, received.get());
    assertTrue(localTransportA.commands.isEmpty());
  }

  @Test
  void invalidSignatureIsRejectedBeforeTheOwnerExecutes() {
    ClusterEnvironmentIdeService bad = serviceWithSecret("wrong-cluster-secret-0123456789abcdef");

    assertThrows(
        AiEnvironmentIdeUnavailableException.class, () -> bad.openView(ENV, openViewRequest(null)));

    assertEquals(1, received.get());
    assertTrue(transportB.commands.isEmpty());
  }

  @Test
  void oversizedResponseBodyIsRejectedByTheTotalByteCap() throws IOException {
    HttpServer oversizeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    oversizeServer.createContext(
        "/internal/environment-ide/",
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

      assertThrows(
          AiEnvironmentIdeUnavailableException.class,
          () -> service.openView(ENV, openViewRequest(null)));
    } finally {
      oversizeServer.stop(0);
    }
  }

  @Test
  void stalledResponseBodyHitsTheClientDeadlineInsteadOfHanging() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    HttpServer stalledServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stalledServer.createContext(
        "/internal/environment-ide/",
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
      assertThrows(
          AiEnvironmentIdeUnavailableException.class,
          () -> service.openView(ENV, openViewRequest(null)));
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

    assertThrows(AiValidationException.class, () -> nodeAService.openView(ENV, request));
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
        new InMemoryIdeBootstrapTicketService(),
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
      nodeBAuthentication.verify(exchange.getRequestMethod(), path, headers);
    } catch (ClusterInternalAuthenticationException error) {
      respond(exchange, 401, "{\"message\":\"internal authentication failed\"}");
      return;
    }
    EnvironmentId environmentId = environmentIdFrom(path);
    String method = exchange.getRequestMethod();
    String[] segments = path.split("/");
    try {
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
      if ("PUT".equals(method) && segments.length == 7 && "directory".equals(segments[6])) {
        EnvironmentIdeChangeDirectoryDTO request =
            objectMapper.readValue(
                exchange.getRequestBody(), EnvironmentIdeChangeDirectoryDTO.class);
        respond(
            exchange,
            200,
            objectMapper.writeValueAsString(
                nodeBLocal.changeDirectory(environmentId, segments[5], request)));
        return;
      }
      if ("POST".equals(method) && segments.length == 7 && "terminal".equals(segments[6])) {
        EnvironmentIdeViewBindingDTO request =
            objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeViewBindingDTO.class);
        nodeBLocal.focusTerminal(environmentId, segments[5], request);
        respond(exchange, 204, "");
        return;
      }
      if ("POST".equals(method) && segments.length == 7 && "detach".equals(segments[6])) {
        EnvironmentIdeViewBindingDTO request =
            objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeViewBindingDTO.class);
        nodeBLocal.detachView(environmentId, segments[5], request);
        respond(exchange, 204, "");
        return;
      }
      if ("DELETE".equals(method) && segments.length == 6) {
        EnvironmentIdeCloseViewDTO request =
            objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeCloseViewDTO.class);
        nodeBLocal.closeView(environmentId, segments[5], request);
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
    return EnvironmentId.parse(segments[3]);
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
    request.setMode("create");
    request.setDirectory(directory);
    return request;
  }

  private static EnvironmentIdeViewBindingDTO binding(String epoch) {
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

  private static final class RecordingTransport implements IdeCommandTransport {

    private final List<IdeCommand> commands = new ArrayList<>();

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeReply> reply = new CompletableFuture<>();
      reply.complete(replyFor(command));
      return new IdeRequest(
          new DaemonConnectionIdentity(environmentId, "daemon-instance"), UUID.randomUUID(), reply);
    }

    private static IdeReply replyFor(IdeCommand command) {
      if (command.action() == IdeAction.LIST_VIEWS) {
        return new IdeReply(
            true, null, null, null, null, null, null, null, null, List.of(), null, null);
      }
      String directory =
          command.action() == IdeAction.CHANGE_DIRECTORY
              ? command.directory()
              : "/home/dev/project";
      return new IdeReply(
          true,
          UUID.fromString(command.viewId()),
          command.documentId() == null ? null : UUID.fromString(command.documentId()),
          command.eventNodeId() == null ? null : UUID.fromString(command.eventNodeId()),
          command.connectionId() == null ? null : UUID.fromString(command.connectionId()),
          command.claimId() == null ? null : UUID.fromString(command.claimId()),
          7L,
          directory,
          "/daemon/workspaces/" + command.viewId(),
          null,
          null,
          null);
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(new DaemonConnectionIdentity(environmentId, "daemon-instance"));
    }

    @Override
    public void sendIdeLease(EnvironmentId environmentId, DaemonIdeLease lease) {
      throw new AssertionError("unexpected lease");
    }

    @Override
    public void abandonIde(UUID invocationId) {}
  }

  private static final class AllowGateway implements IdeBrowserUseGateway {

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
