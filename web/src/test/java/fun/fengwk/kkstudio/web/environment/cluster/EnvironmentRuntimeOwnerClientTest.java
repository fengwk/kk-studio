package fun.fengwk.kkstudio.web.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** owner 单跳 HTTP 客户端：固定路径、内部认证、有界响应与错误映射都必须真实执行。 */
class EnvironmentRuntimeOwnerClientTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private HttpServer server;
  private URI origin;
  private ClusterInternalAuthentication authentication;
  private EnvironmentRuntimeOwnerClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.start();
    origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    authentication = mock(ClusterInternalAuthentication.class);
    when(authentication.sign(any(), any(), any())).thenReturn(Map.of("X-Test-Auth", "ok"));
    client =
        new EnvironmentRuntimeOwnerClient(
            HttpClient.newHttpClient(), authentication, Duration.ofSeconds(2));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void readsRemoteRuntimeSnapshotWithSignedFixedPath() {
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    server.createContext(
        "/internal/environments/" + ENV + "/runtime",
        exchange -> {
          method.set(exchange.getRequestMethod());
          path.set(exchange.getRequestURI().getRawPath());
          auth.set(exchange.getRequestHeaders().getFirst("X-Test-Auth"));
          respond(
              exchange,
              200,
              "{\"revision\":\"7\",\"status\":{\"daemonVersion\":\"1.0.9\",\"update\":null}}");
        });

    var snapshot = client.runtimeStatus(remote(), ENV);

    assertEquals("GET", method.get());
    assertEquals("/internal/environments/" + ENV + "/runtime", path.get());
    assertEquals("ok", auth.get());
    assertEquals("7", snapshot.getRevision());
    assertEquals("1.0.9", snapshot.getStatus().getDaemonVersion());
  }

  @Test
  void postsUpdateAndDecodesExplicitCommandStatus() {
    AtomicReference<String> method = new AtomicReference<>();
    server.createContext(
        "/internal/environments/" + ENV + "/update",
        exchange -> {
          method.set(exchange.getRequestMethod());
          respond(
              exchange,
              200,
              "{\"runtimeStatus\":{\"revision\":\"8\",\"status\":{\"daemonVersion\":\"1.0.9\","
                  + "\"update\":{\"targetVersion\":\"1.0.10\",\"downloadedBytes\":\"12\","
                  + "\"totalBytes\":null}}},"
                  + "\"commandStatus\":\"SENT_UNCONFIRMED\"}");
        });

    EnvironmentUpdateStartDTO result = client.startUpdate(remote(), ENV);

    assertEquals("POST", method.get());
    assertEquals(EnvironmentUpdateStartDTO.SENT_UNCONFIRMED, result.getCommandStatus());
    assertEquals(8L, Long.parseLong(result.getRuntimeStatus().getRevision()));
    assertEquals(12L, result.getRuntimeStatus().getStatus().getUpdate().getDownloadedBytes());
  }

  @Test
  void mapsBusyAndUnavailableWithoutLeakingBody() {
    server.createContext(
        "/internal/environments/" + ENV + "/update",
        exchange -> respond(exchange, 409, "{\"code\":\"in_use\",\"message\":\"busy\"}"));
    assertThrows(AiInUseException.class, () -> client.startUpdate(remote(), ENV));

    server.removeContext("/internal/environments/" + ENV + "/update");
    server.createContext(
        "/internal/environments/" + ENV + "/runtime",
        exchange ->
            respond(
                exchange,
                409,
                "{\"code\":\"environment_unavailable\",\"message\":\"unavailable\"}"));
    assertThrows(
        AiEnvironmentUnavailableException.class, () -> client.runtimeStatus(remote(), ENV));
  }

  @Test
  void rejectsUntrustedOriginAndMalformedSuccess() {
    ClusterOwnerRoute withPath =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create(origin + "/unsafe"));
    assertThrows(
        AiEnvironmentUnavailableException.class, () -> client.runtimeStatus(withPath, ENV));

    server.createContext(
        "/internal/environments/" + ENV + "/runtime",
        exchange -> respond(exchange, 200, "{\"revision\":\"7\",\"status\":{\"extra\":true}}"));
    assertThrows(
        AiEnvironmentUnavailableException.class, () -> client.runtimeStatus(remote(), ENV));
  }

  @Test
  void rejectsOversizedOwnerResponse() {
    server.createContext(
        "/internal/environments/" + ENV + "/runtime",
        exchange ->
            respond(
                exchange,
                200,
                "{\"revision\":\"7\",\"status\":{\"daemonVersion\":\"1.0.9\","
                    + "\"update\":null},\"padding\":\""
                    + "x".repeat(EnvironmentRuntimeOwnerClient.MAX_RESPONSE_BYTES)
                    + "\"}"));

    assertThrows(
        AiEnvironmentUnavailableException.class, () -> client.runtimeStatus(remote(), ENV));
  }

  @Test
  void timesOutSlowOwner() {
    EnvironmentRuntimeOwnerClient shortTimeoutClient =
        new EnvironmentRuntimeOwnerClient(
            HttpClient.newHttpClient(), authentication, Duration.ofMillis(100));
    server.createContext(
        "/internal/environments/" + ENV + "/runtime",
        exchange -> {
          try {
            Thread.sleep(500);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, 200, "{}");
        });

    assertThrows(
        AiEnvironmentUnavailableException.class,
        () -> shortTimeoutClient.runtimeStatus(remote(), ENV));
  }

  private ClusterOwnerRoute remote() {
    return ClusterOwnerRoute.remote(UUID.randomUUID(), origin);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
