package fun.fengwk.kkstudio.platform.environment.cluster;

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
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;
import fun.fengwk.kkstudio.platform.environment.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

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
 * 意图：真实 HTTP 一跳验证 A→B 元数据路由——A 只发一次已签名内部请求，B 校验认证后由 owner 本地出票，返回的 ViewDTO 携带 B 铸造的 bootstrap 入口；A
 * 的本地 delegate 从不被调用。路由失效时 A 在任何网络调用前 fail-closed，B 不收到请求。
 */
class ClusterEnvironmentIdeRemoteMetadataIntegrationTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
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
  private AtomicInteger received;
  private ClusterEnvironmentIdeService nodeAService;

  @BeforeEach
  void setUp() throws IOException {
    received = new AtomicInteger();
    nodeBAuthentication =
        new HmacClusterInternalAuthentication(
            SECRET, NODE_B, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    IdeOriginProperties originB = new IdeOriginProperties();
    originB.setOriginTemplate("https://{environmentId}.ide.example.com");
    RecordingTransport transportB = new RecordingTransport();
    transportB.reply = IdeReply.ok(VIEW, "/home/dev/project", "/daemon/workspaces/" + VIEW);
    nodeBLocal =
        new EnvironmentIdeServiceImpl(transportB, originB, new InMemoryIdeBootstrapTicketService());

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/environment-ide/", this::handle);
    server.start();
    int port = server.getAddress().getPort();

    registryA.current =
        Optional.of(ClusterIdeOwnerRoute.remote(NODE_B, URI.create("http://127.0.0.1:" + port)));
    ClusterInternalClient clientA =
        new ClusterInternalClient(
            HttpClient.newHttpClient(),
            objectMapper,
            new HmacClusterInternalAuthentication(
                SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024));
    EnvironmentIdeServiceImpl localA =
        new EnvironmentIdeServiceImpl(
            localTransportA, originB, new InMemoryIdeBootstrapTicketService());
    nodeAService = new ClusterEnvironmentIdeService(registryA, clientA, localA);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void remoteOpenViewIsOneHopAndTheOwnerIssuesTheBootstrapEntry() {
    EnvironmentIdeViewDTO dto = nodeAService.openView(ENV, openViewRequest());

    assertEquals(VIEW, dto.getViewId());
    assertEquals(ENV.toString(), dto.getEnvironmentId());
    assertTrue(
        dto.getEntryUrl().startsWith("https://" + ENV + ".ide.example.com/__ide/bootstrap?token="),
        () -> "unexpected entry url: " + dto.getEntryUrl());
    assertEquals(1, received.get());
    assertTrue(localTransportA.commands.isEmpty());
  }

  @Test
  void unavailableRouteFailsClosedWithoutContactingTheRemoteOwner() {
    registryA.current = Optional.empty();

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> nodeAService.openView(ENV, openViewRequest()));
    assertEquals(0, received.get());
    assertTrue(localTransportA.commands.isEmpty());
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
      ClusterInternalClient smallClient =
          new ClusterInternalClient(
              HttpClient.newHttpClient(),
              objectMapper,
              new HmacClusterInternalAuthentication(
                  SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024),
              Duration.ofSeconds(5),
              1024);
      StubRegistry registry = new StubRegistry();
      registry.current =
          Optional.of(
              ClusterIdeOwnerRoute.remote(
                  NODE_B, URI.create("http://127.0.0.1:" + oversizeServer.getAddress().getPort())));
      EnvironmentIdeServiceImpl local =
          new EnvironmentIdeServiceImpl(
              localTransportA, new IdeOriginProperties(), new InMemoryIdeBootstrapTicketService());
      ClusterEnvironmentIdeService service =
          new ClusterEnvironmentIdeService(registry, smallClient, local);

      assertThrows(
          AiEnvironmentIdeUnavailableException.class,
          () -> service.openView(ENV, openViewRequest()));
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
          // 先发合法响应头并以 chunked 开始响应体，随后保持连接不结束，模拟响应体停滞。
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
      ClusterInternalClient shortDeadlineClient =
          new ClusterInternalClient(
              HttpClient.newHttpClient(),
              objectMapper,
              new HmacClusterInternalAuthentication(
                  SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024),
              Duration.ofMillis(300),
              64 * 1024);
      StubRegistry registry = new StubRegistry();
      registry.current =
          Optional.of(
              ClusterIdeOwnerRoute.remote(
                  NODE_B, URI.create("http://127.0.0.1:" + stalledServer.getAddress().getPort())));
      EnvironmentIdeServiceImpl local =
          new EnvironmentIdeServiceImpl(
              localTransportA, new IdeOriginProperties(), new InMemoryIdeBootstrapTicketService());
      ClusterEnvironmentIdeService service =
          new ClusterEnvironmentIdeService(registry, shortDeadlineClient, local);

      long start = System.nanoTime();
      assertThrows(
          AiEnvironmentIdeUnavailableException.class,
          () -> service.openView(ENV, openViewRequest()));
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
  void oversizedMetadataRequestIsRejectedBeforeAnyNetworkCall() {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setDirectory("x".repeat(20 * 1024));

    assertThrows(AiValidationException.class, () -> nodeAService.openView(ENV, request));
    assertEquals(0, received.get());
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
    if (!"POST".equals(exchange.getRequestMethod()) || !path.endsWith("/views")) {
      respond(exchange, 404, "{\"message\":\"unsupported internal IDE command\"}");
      return;
    }
    EnvironmentId environmentId = environmentIdFrom(path);
    EnvironmentIdeOpenViewDTO request =
        objectMapper.readValue(exchange.getRequestBody(), EnvironmentIdeOpenViewDTO.class);
    EnvironmentIdeViewDTO dto = nodeBLocal.openView(environmentId, request);
    respond(exchange, 200, objectMapper.writeValueAsString(dto));
  }

  private static EnvironmentId environmentIdFrom(String path) {
    String[] segments = path.split("/");
    return EnvironmentId.parse(segments[3]);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static EnvironmentIdeOpenViewDTO openViewRequest() {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setDirectory("/home/dev/project");
    return request;
  }

  private static final class StubRegistry implements ClusterNodeRegistry {

    private Optional<ClusterIdeOwnerRoute> current = Optional.empty();

    @Override
    public Optional<ClusterIdeOwnerRoute> route(EnvironmentId environmentId) {
      return current;
    }
  }

  private static final class RecordingTransport implements IdeCommandTransport {

    private final List<IdeCommand> commands = new ArrayList<>();
    private IdeReply reply = IdeReply.ok(VIEW, null, null);

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeReply> future = new CompletableFuture<>();
      future.complete(reply);
      return new IdeRequest(
          new IdeSessionIdentity(environmentId, "daemon-instance", UUID.randomUUID()),
          UUID.randomUUID(),
          future);
    }

    @Override
    public Optional<IdeSessionIdentity> ideSessionIdentity(EnvironmentId environmentId) {
      return Optional.of(
          new IdeSessionIdentity(environmentId, "daemon-instance", UUID.randomUUID()));
    }

    @Override
    public void abandonIde(UUID invocationId) {}
  }
}
