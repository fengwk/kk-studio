package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeBus;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimePayloadBuffer;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterRealtimePayloadClient;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 端到端：owner sink 写出 REFERENCE，接收节点通过真实 JDK HttpServer 内部认证回拉完整 unicode ToolPartial，并在本地只投递一次。 */
class RealtimePayloadClusterRoundTripTest {

  private static final UUID OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID RECEIVER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID UNKNOWN = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final UUID THREAD = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_THREAD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID TOOL_INVOCATION =
      UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID EVENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
  private static final int MAX_NOTIFICATION_BYTES = 7900;

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();
  private final RealtimePayloadBuffer payloadBuffer = new RealtimePayloadBuffer();
  private final ExecutorService resolver = Executors.newSingleThreadExecutor();
  private final HmacClusterInternalAuthentication authentication =
      new HmacClusterInternalAuthentication(
          SECRET, OWNER, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
  private final AtomicInteger requests = new AtomicInteger();
  private HttpServer server;
  private ExecutorService executor;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor =
        Executors.newCachedThreadPool(
            Thread.ofPlatform().name("realtime-roundtrip-server-", 0L).daemon(true).factory());
    server.setExecutor(executor);
    server.createContext(
        ClusterInternalPaths.INTERNAL_REALTIME_PREFIX,
        exchange -> {
          requests.incrementAndGet();
          try {
            serve(exchange);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  void largeUnicodeToolPartialRoundTripsCompletelyAndIsDeliveredOnce() throws InterruptedException {
    RealtimeEvent.ToolPartial event = toolPartial("界".repeat(5_000));
    String envelope = codec.encodeEvent(OWNER, event);
    String canonical = codec.encodeEventPayload(event);
    assertTrue(
        envelope.getBytes(StandardCharsets.UTF_8).length > MAX_NOTIFICATION_BYTES,
        "fixture must take the oversize path");

    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    PostgresqlRealtimeEventSink ownerSink =
        new PostgresqlRealtimeEventSink(jdbc, codec, OWNER, payloadBuffer);
    ownerSink.append(event);

    RealtimeNotificationCodec.Envelope.Reference reference =
        assertInstanceOf(
            RealtimeNotificationCodec.Envelope.Reference.class, codec.decode(jdbc.payload));
    assertEquals(OWNER, reference.originNodeId());
    assertEquals(THREAD, reference.threadId());
    assertEquals(canonical, payloadBuffer.get(reference.payloadId()).orElseThrow().canonicalJson());

    List<RealtimeEvent> received = new ArrayList<>();
    RealtimeBus receiver =
        new RealtimeBus(
            RECEIVER,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            ignored -> {
              throw new AssertionError("fetched payload must not be re-published");
            },
            () -> false,
            payloadClient(),
            payloadBuffer,
            resolver);
    try (receiver) {
      receiver.subscribe(THREAD, received::add, () -> {});

      receiver.onNotification(jdbc.payload);
      awaitResolved();

      assertEquals(List.of(event), received);
      assertEquals(PostgresqlRealtimeEventSource.CHANNEL, jdbc.channel);
      assertEquals(1, requests.get());
    }
  }

  @Test
  void wrongOriginReferenceResyncsWithoutFetchingOrDelivering() throws InterruptedException {
    UUID payloadId =
        payloadBuffer.put(THREAD, codec.encodeEventPayload(toolPartial("x"))).orElseThrow();
    AtomicInteger resyncs = new AtomicInteger();
    List<RealtimeEvent> received = new ArrayList<>();
    RealtimeBus receiver =
        new RealtimeBus(
            RECEIVER,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            ignored -> {
              throw new AssertionError("must not echo");
            },
            () -> false,
            payloadClient(),
            payloadBuffer,
            resolver);
    try (receiver) {
      receiver.subscribe(THREAD, received::add, resyncs::incrementAndGet);

      receiver.onNotification(codec.encodeReference(UNKNOWN, THREAD, payloadId));
      awaitResolved();

      assertEquals(List.of(), received);
      assertEquals(1, resyncs.get());
      assertEquals(0, requests.get());
    }
  }

  @Test
  void fetchedPayloadForAnotherThreadResyncsWithoutDelivery() throws InterruptedException {
    UUID payloadId =
        payloadBuffer.put(THREAD, codec.encodeEventPayload(toolPartial("thread-a"))).orElseThrow();
    AtomicInteger resyncs = new AtomicInteger();
    List<RealtimeEvent> received = new ArrayList<>();
    RealtimeBus receiver =
        new RealtimeBus(
            RECEIVER,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            ignored -> {
              throw new AssertionError("must not echo");
            },
            () -> false,
            payloadClient(),
            payloadBuffer,
            resolver);
    try (receiver) {
      receiver.subscribe(OTHER_THREAD, received::add, resyncs::incrementAndGet);

      receiver.onNotification(codec.encodeReference(OWNER, OTHER_THREAD, payloadId));
      awaitResolved();

      assertEquals(List.of(), received);
      assertEquals(1, resyncs.get());
      assertEquals(1, requests.get());
    }
  }

  /** REFERENCE 在 resolver 上异步回拉；关闭 resolver 并等待它跑完本次通道后再断言。 */
  private void awaitResolved() throws InterruptedException {
    resolver.shutdown();
    assertTrue(resolver.awaitTermination(5, TimeUnit.SECONDS));
  }

  private ClusterRealtimePayloadClient payloadClient() {
    Registry registry =
        new Registry(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    return new ClusterRealtimePayloadClient(HttpClient.newHttpClient(), authentication, registry);
  }

  private void serve(HttpExchange exchange) throws IOException {
    String rawPath = exchange.getRequestURI().getRawPath();
    String rawQuery = exchange.getRequestURI().getRawQuery();
    String canonical = rawQuery == null ? rawPath : rawPath + "?" + rawQuery;
    try {
      authentication.verify("GET", canonical, authHeaders(exchange.getRequestHeaders()));
    } catch (ClusterInternalAuthenticationException error) {
      respond(exchange, 401, "{\"message\":\"internal authentication failed\"}");
      return;
    }
    String payloadIdText = rawPath.substring(rawPath.lastIndexOf('/') + 1);
    Optional<RealtimePayloadBuffer.Payload> payload =
        payloadBuffer.get(UUID.fromString(payloadIdText));
    if (payload.isEmpty()) {
      respond(exchange, 404, "");
      return;
    }
    respond(exchange, 200, payload.get().canonicalJson());
  }

  private static Map<String, String> authHeaders(Headers requestHeaders) {
    Map<String, String> headers = new HashMap<>();
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
    return headers;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static RealtimeEvent.ToolPartial toolPartial(String text) {
    return new RealtimeEvent.ToolPartial(
        THREAD,
        TOOL_INVOCATION,
        1,
        EVENT_ID,
        new ToolResult("call-1", List.of(new TextResultContent(text)), false, "{}"),
        NOW);
  }

  /** 只接收单条 pg_notify 的 JdbcTemplate 替身；reference 内容即真实通知 payload。 */
  private static final class RecordingJdbcTemplate extends JdbcTemplate {

    private String payload;
    private String channel;

    @Override
    public <T> T execute(String sql, PreparedStatementCallback<T> action) {
      PreparedStatement statement =
          (PreparedStatement)
              Proxy.newProxyInstance(
                  PreparedStatement.class.getClassLoader(),
                  new Class<?>[] {PreparedStatement.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("setString")) {
                      int index = (int) args[0];
                      if (index == 1) {
                        channel = (String) args[1];
                      } else if (index == 2) {
                        payload = (String) args[1];
                      }
                      return null;
                    }
                    if (method.getName().equals("execute")) {
                      return true;
                    }
                    return null;
                  });
      try {
        return action.doInPreparedStatement(statement);
      } catch (SQLException error) {
        throw new UncategorizedSQLException("cannot execute realtime notification", sql, error);
      }
    }
  }

  private record Registry(URI ownerOrigin) implements ClusterNodeRegistry {

    @Override
    public Optional<ClusterIdeOwnerRoute> route(EnvironmentId environmentId) {
      return Optional.empty();
    }

    @Override
    public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
      return OWNER.equals(nodeInstanceId) ? Optional.of(ownerOrigin) : Optional.empty();
    }
  }
}
