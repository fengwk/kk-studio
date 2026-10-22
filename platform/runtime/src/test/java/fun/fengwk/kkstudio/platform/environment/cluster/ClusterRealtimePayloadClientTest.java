package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimePayloadUnavailableException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Realtime payload 回拉客户端：真实 JDK HttpServer 上的认证、origin 租约、缺失/过期、响应上界、慢 body 总超时与失败后继续可用。 */
class ClusterRealtimePayloadClientTest {

  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID THREAD = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID PAYLOAD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";
  private static final String EVENT_JSON =
      "{\"threadId\":\"11111111-1111-1111-1111-111111111111\",\"text\":\"界面\"}";

  private final HmacClusterInternalAuthentication verifier =
      new HmacClusterInternalAuthentication(
          SECRET, NODE_B, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
  private final HmacClusterInternalAuthentication signer =
      new HmacClusterInternalAuthentication(
          SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
  private ServerFixture server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void fetchesCanonicalPayloadWithUnicodeUsingTrustedLeaseOrigin() {
    server = new ServerFixture(this::serveEvent);
    StubRegistry registry = new StubRegistry();
    registry.origins.put(NODE_B, server.origin());
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(HttpClient.newHttpClient(), signer, registry);

    String body = client.fetch(NODE_B, THREAD, PAYLOAD);

    assertEquals(EVENT_JSON, body);
    assertEquals(ClusterInternalPaths.realtimePayloadPath(PAYLOAD), server.lastCanonicalPath.get());
    assertEquals(NODE_B.toString(), server.lastTarget.get());
  }

  @Test
  void rejectsUnauthenticatedOriginResponse() {
    server =
        new ServerFixture(
            exchange -> {
              respond(exchange, 401, "{\"message\":\"internal authentication failed\"}");
            });
    StubRegistry registry = new StubRegistry();
    registry.origins.put(NODE_B, server.origin());
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(HttpClient.newHttpClient(), signer, registry);

    assertThrows(
        RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
  }

  @Test
  void rejectsSignatureForAnotherTargetInstance() {
    server = new ServerFixture(this::serveEvent);
    StubRegistry registry = new StubRegistry();
    registry.origins.put(NODE_B, server.origin());
    ClusterInternalAuthentication wrongTargetSigner =
        new ClusterInternalAuthentication() {
          @Override
          public Map<String, String> sign(String method, String requestPath, UUID targetNodeId) {
            return signer.sign(method, requestPath, NODE_A);
          }

          @Override
          public void verify(String method, String requestPath, Map<String, String> headers) {
            throw new UnsupportedOperationException();
          }
        };
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(HttpClient.newHttpClient(), wrongTargetSigner, registry);

    assertThrows(
        RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
  }

  @Test
  void missingLiveOriginIsUnavailableWithoutHttpRequest() {
    server = new ServerFixture(this::serveEvent);
    AtomicInteger requests = new AtomicInteger();
    server.requestCount = requests;
    StubRegistry registry = new StubRegistry();
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(HttpClient.newHttpClient(), signer, registry);

    assertThrows(
        RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
    assertEquals(0, requests.get());
  }

  @Test
  void expiredPayloadReturnsUnavailable() {
    server =
        new ServerFixture(
            exchange -> {
              authenticate(exchange);
              respond(exchange, 404, "");
            });
    StubRegistry registry = new StubRegistry();
    registry.origins.put(NODE_B, server.origin());
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(HttpClient.newHttpClient(), signer, registry);

    assertThrows(
        RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
  }

  @Test
  void responseAboveBoundIsRejected() {
    server =
        new ServerFixture(
            exchange -> {
              authenticate(exchange);
              respond(exchange, 200, "x".repeat(17));
            });
    StubRegistry registry = new StubRegistry();
    registry.origins.put(NODE_B, server.origin());
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(
            HttpClient.newHttpClient(), signer, registry, Duration.ofSeconds(2), 16);

    assertThrows(
        RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
  }

  @Test
  void slowBodyIsCutOffByTotalDeadlineAndClientRemainsUsable() throws Exception {
    server =
        new ServerFixture(
            exchange -> {
              authenticate(exchange);
              if (server.slow) {
                slowBody(exchange);
              } else {
                respond(exchange, 200, EVENT_JSON);
              }
            });
    StubRegistry registry = new StubRegistry();
    registry.origins.put(NODE_B, server.origin());
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(
            HttpClient.newHttpClient(), signer, registry, Duration.ofMillis(500), 1024);
    server.slow = true;

    long started = System.nanoTime();
    assertThrows(
        RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(elapsedMillis < 3_000, "body read must respect the total deadline");

    server.slow = false;
    assertEquals(EVENT_JSON, client.fetch(NODE_B, THREAD, PAYLOAD));
  }

  @Test
  void timeoutAndInterruptionCancelExchangeBeforeResponseHeadersArrive() {
    // 响应头之前还没有 body subscription：必须取消 HTTP future，而不只是尝试取消不存在的 subscription。
    for (boolean interrupt : new boolean[] {false, true}) {
      HttpClient httpClient = mock(HttpClient.class);
      CompletableFuture<HttpResponse<byte[]>> response = new CompletableFuture<>();
      when(httpClient.<byte[]>sendAsync(any(HttpRequest.class), any())).thenReturn(response);
      StubRegistry registry = new StubRegistry();
      registry.origins.put(NODE_B, URI.create("http://node.internal"));
      ClusterRealtimePayloadClient client =
          new ClusterRealtimePayloadClient(
              httpClient, signer, registry, Duration.ofMillis(20), 1024);
      if (interrupt) {
        Thread.currentThread().interrupt();
      }
      try {
        assertThrows(
            RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
        assertTrue(response.isCancelled(), "the in-flight HTTP exchange must be cancelled");
        assertEquals(interrupt, Thread.currentThread().isInterrupted());
        verify(httpClient).sendAsync(any(HttpRequest.class), any());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void rejectsMalformedRegistryOriginWithoutRequest() {
    server = new ServerFixture(this::serveEvent);
    AtomicInteger requests = new AtomicInteger();
    server.requestCount = requests;
    StubRegistry registry = new StubRegistry();
    registry.origins.put(NODE_B, URI.create("http://user@127.0.0.1:" + server.port()));
    ClusterRealtimePayloadClient client =
        new ClusterRealtimePayloadClient(HttpClient.newHttpClient(), signer, registry);

    assertThrows(
        RealtimePayloadUnavailableException.class, () -> client.fetch(NODE_B, THREAD, PAYLOAD));
    assertEquals(0, requests.get());
  }

  private void serveEvent(HttpExchange exchange) throws IOException {
    authenticate(exchange);
    respond(exchange, 200, EVENT_JSON);
  }

  private void authenticate(HttpExchange exchange) {
    String rawPath = exchange.getRequestURI().getRawPath();
    String rawQuery = exchange.getRequestURI().getRawQuery();
    String canonical = rawQuery == null ? rawPath : rawPath + "?" + rawQuery;
    Map<String, String> headers = new HashMap<>();
    Headers requestHeaders = exchange.getRequestHeaders();
    for (String name :
        new String[] {
          HmacClusterInternalAuthentication.HEADER_TARGET,
          HmacClusterInternalAuthentication.HEADER_TIMESTAMP,
          HmacClusterInternalAuthentication.HEADER_NONCE,
          HmacClusterInternalAuthentication.HEADER_SIGNATURE
        }) {
      String value = requestHeaders.getFirst(name);
      if (value != null) {
        headers.put(name, value);
      }
    }
    verifier.verify("GET", canonical, headers);
    server.lastCanonicalPath.set(canonical);
    server.lastTarget.set(headers.get(HmacClusterInternalAuthentication.HEADER_TARGET));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static void slowBody(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, 64 * 1024);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write('x');
      output.flush();
      try {
        Thread.sleep(5_000);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** 真实内部 HTTP 服务：每请求独立认证，行为由 handler 决定。 */
  private final class ServerFixture implements AutoCloseable {

    private final HttpServer httpServer;
    private final ExecutorService executor =
        Executors.newCachedThreadPool(
            Thread.ofPlatform().name("realtime-payload-test-server-", 0L).daemon(true).factory());
    private final AtomicReference<String> lastCanonicalPath = new AtomicReference<>();
    private final AtomicReference<String> lastTarget = new AtomicReference<>();
    private volatile boolean slow;
    private AtomicInteger requestCount = new AtomicInteger();

    private ServerFixture(Handler handler) {
      try {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
      httpServer.setExecutor(executor);
      httpServer.createContext(
          ClusterInternalPaths.INTERNAL_REALTIME_PREFIX,
          exchange -> {
            requestCount.incrementAndGet();
            try {
              handler.handle(exchange);
            } catch (IOException | RuntimeException error) {
              try {
                respond(exchange, 500, "");
              } catch (IOException ignored) {
                // 客户端已取消/关闭连接时不再回写错误体。
              }
            } finally {
              exchange.close();
            }
          });
      httpServer.start();
    }

    private URI origin() {
      return URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
    }

    private int port() {
      return httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
      httpServer.stop(0);
      executor.shutdownNow();
    }
  }

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private static final class StubRegistry implements ClusterNodeRegistry {

    private final Map<UUID, URI> origins = new HashMap<>();

    @Override
    public Optional<ClusterOwnerRoute> route(EnvironmentId environmentId) {
      return Optional.empty();
    }

    @Override
    public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
      return Optional.ofNullable(origins.get(nodeInstanceId));
    }
  }
}
