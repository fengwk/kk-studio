package fun.fengwk.kkstudio.ide.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 意图：查询只走本地 registry 或 cluster_node 解析出的单跳事件节点，认证路径精确且不重试。 */
class IdeBrowserUseClientTest {

  private static final UUID LOCAL_NODE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID REMOTE_NODE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID CONNECTION = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final UUID DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final UUID VIEW = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID CLAIM = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void localNodeReadsOnlyTheLocalRegistry() {
    IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();
    long generation = registry.register(new ConnectionIdentity(LOCAL_NODE, CONNECTION));
    registry.accept(IDENTITY_LOCAL, generation, usage(1, boundView(5L)));
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    IdeBrowserUseClient client = client(registry, nodes, null);

    assertTrue(client.findExactIntent(IDENTITY_LOCAL, DOCUMENT, ENVIRONMENT, VIEW, CLAIM));
    assertEquals(
        1, client.activeBoundProofs(IDENTITY_LOCAL, DOCUMENT).orElseThrow().views().size());
    verifyNoInteractions(nodes);
  }

  @Test
  void remoteExactIntentUsesAuthenticatedSingleHopAndParsesTheReply() throws Exception {
    startServer(
        exchange -> {
          verifyAuth("POST", exchange);
          assertEquals("POST", exchange.getRequestMethod());
          assertEquals(IdeBrowserUsePaths.intentPath(), exchange.getRequestURI().toString());
          respond(exchange, 200, "{\"matched\":true}");
        });
    IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenReturn(Optional.of(origin()));
    IdeBrowserUseClient client = client(registry, nodes, authentication(REMOTE_NODE));

    assertTrue(client.findExactIntent(IDENTITY_REMOTE, DOCUMENT, ENVIRONMENT, VIEW, CLAIM));
  }

  @Test
  void remoteBoundProofsReturnsOnlyTheTypedActiveSnapshot() throws Exception {
    IdeBrowserUse active = usage(7, boundView(12L));
    IdeBrowserUseSnapshot snapshot = IdeBrowserUseSnapshot.from(IDENTITY_REMOTE, active);
    byte[] payload = MAPPER.writeValueAsBytes(snapshot);
    startServer(
        exchange -> {
          verifyAuth("GET", exchange);
          assertEquals(
              IdeBrowserUsePaths.activeProofsPath(IDENTITY_REMOTE, DOCUMENT),
              exchange.getRequestURI().toString());
          respond(exchange, 200, new String(payload, StandardCharsets.UTF_8));
        });
    IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenReturn(Optional.of(origin()));
    IdeBrowserUseClient client = client(registry, nodes, authentication(REMOTE_NODE));

    IdeBrowserUse proofs = client.activeBoundProofs(IDENTITY_REMOTE, DOCUMENT).orElseThrow();
    assertEquals(7, proofs.revision());
    assertEquals(12L, proofs.views().getFirst().bindingEpoch());
  }

  @Test
  void noLiveOriginFailsWithoutAnyAttempt() {
    IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenReturn(Optional.empty());
    IdeBrowserUseClient client = client(registry, nodes, authentication(LOCAL_NODE));

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> client.findExactIntent(IDENTITY_REMOTE, DOCUMENT, ENVIRONMENT, VIEW, CLAIM));
  }

  @Test
  void serverFailureIsNotRetried() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    startServer(
        exchange -> {
          verifyAuth("GET", exchange);
          requests.incrementAndGet();
          respond(exchange, 503, "{\"message\":\"busy\"}");
        });
    IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenReturn(Optional.of(origin()));
    IdeBrowserUseClient client = client(registry, nodes, authentication(REMOTE_NODE));

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> client.activeBoundProofs(IDENTITY_REMOTE, DOCUMENT));
    assertEquals(1, requests.get());
  }

  @Test
  void emptyBoundProofSnapshotIsNotAcceptedAsSuccess() throws Exception {
    startServer(
        exchange -> {
          verifyAuth("GET", exchange);
          respond(
              exchange,
              200,
              "{\"eventNodeId\":\""
                  + REMOTE_NODE
                  + "\",\"connectionId\":\""
                  + CONNECTION
                  + "\",\"documentId\":\""
                  + DOCUMENT
                  + "\",\"revision\":\"7\",\"views\":[]}");
        });
    IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenReturn(Optional.of(origin()));
    IdeBrowserUseClient client = client(registry, nodes, authentication(REMOTE_NODE));

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> client.activeBoundProofs(IDENTITY_REMOTE, DOCUMENT));
  }

  @Test
  void missingBoundProofsIsAnEmptyResultNotAFabricatedSuccess() throws Exception {
    startServer(
        exchange -> {
          verifyAuth("GET", exchange);
          respond(exchange, 404, "");
        });
    IdeBrowserUseRegistry registry = new IdeBrowserUseRegistry();
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenReturn(Optional.of(origin()));
    IdeBrowserUseClient client = client(registry, nodes, authentication(REMOTE_NODE));

    assertFalse(client.activeBoundProofs(IDENTITY_REMOTE, DOCUMENT).isPresent());
  }

  private static final ConnectionIdentity IDENTITY_LOCAL =
      new ConnectionIdentity(LOCAL_NODE, CONNECTION);
  private static final ConnectionIdentity IDENTITY_REMOTE =
      new ConnectionIdentity(REMOTE_NODE, CONNECTION);

  private IdeBrowserUseClient client(
      IdeBrowserUseRegistry registry,
      ClusterNodeRegistry nodes,
      HmacClusterInternalAuthentication authentication) {
    ClusterInternalAuthentication auth =
        authentication != null
            ? authentication
            : new HmacClusterInternalAuthentication(
                SECRET, LOCAL_NODE, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    return new IdeBrowserUseClient(
        HttpClient.newHttpClient(),
        MAPPER,
        auth,
        nodes,
        registry,
        LOCAL_NODE,
        Duration.ofSeconds(3),
        IdeBrowserUsePaths.MAX_BODY_BYTES);
  }

  private HmacClusterInternalAuthentication authentication(UUID node) {
    return new HmacClusterInternalAuthentication(
        SECRET, node, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
  }

  private void startServer(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        IdeBrowserUsePaths.INTERNAL_PATH,
        exchange -> {
          try {
            handler.handle(exchange);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  private URI origin() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static void verifyAuth(String method, HttpExchange exchange) {
    HmacClusterInternalAuthentication verifier =
        new HmacClusterInternalAuthentication(
            SECRET, REMOTE_NODE, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    Map<String, String> headers = new HashMap<>();
    Headers requestHeaders = exchange.getRequestHeaders();
    for (String name :
        List.of(
            HmacClusterInternalAuthentication.HEADER_TARGET,
            HmacClusterInternalAuthentication.HEADER_TIMESTAMP,
            HmacClusterInternalAuthentication.HEADER_NONCE,
            HmacClusterInternalAuthentication.HEADER_SIGNATURE)) {
      String value = requestHeaders.getFirst(name);
      if (value != null) {
        headers.put(name, value);
      }
    }
    verifier.verify(method, exchange.getRequestURI().toString(), headers);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
  }

  private static IdeBrowserUse usage(long revision, IdeBrowserUseView... views) {
    return new IdeBrowserUse(DOCUMENT, revision, List.of(views));
  }

  private static IdeBrowserUseView boundView(long epoch) {
    return new IdeBrowserUseView(ENVIRONMENT, VIEW, CLAIM, epoch);
  }

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
