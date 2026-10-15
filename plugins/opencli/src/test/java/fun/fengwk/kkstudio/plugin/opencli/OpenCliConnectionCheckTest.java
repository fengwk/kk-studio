package fun.fengwk.kkstudio.plugin.opencli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult.Authentication;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult.BrowserSession;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult.Reachability;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubException.Kind;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 显式只读连接检查的定向契约测试。
 *
 * <p>真实 JDK {@link HttpServer} 覆盖只读路径、Bearer 与私有路由、零生成请求、401/403、重定向不跟随、5xx、畸形/非数组/超限/慢正文、连接失败、无凭据与
 * disabled；另有 mock transport 覆盖 {@link Kind} 到维度的完整映射、中断保留与未定义运行时失败不降级。
 *
 * <p>检查绝不使用生成或提交端点，因此任何测试都断言实际命中的路径集合恰好是只读路径。
 */
class OpenCliConnectionCheckTest {

  private static final String API_KEY = "probe-api-key-97531";
  private static final String INSTANCE_ID = "probe-instance-identity";
  private static final String DATES_PATH = "/api/resources/dates";

  private final List<HttpServer> servers = new ArrayList<>();
  private final List<String> requestLines = new CopyOnWriteArrayList<>();
  private final List<String> otherRequests = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private final AtomicInteger rejectedCredentials = new AtomicInteger();
  private final AtomicReference<PluginCredentialSnapshot> rejectedSnapshot =
      new AtomicReference<>();

  @AfterEach
  void tearDown() {
    for (HttpServer server : servers) {
      server.stop(0);
    }
    servers.clear();
    // 中断类用例必须清掉本线程的中断标志，避免污染同 JVM 的后续测试。
    Thread.interrupted();
  }

  /** 测试意图：未启用时不得触碰 Hub，也不得把本地未探测说成不可达或认证失败。 */
  @Test
  void disabledIntegrationReportsNotCheckedWithoutTouchingTheHub() throws IOException {
    HttpServer server = startDatedServer(200, envelope("[]"), DATES_PATH);
    OpenCliHubClient client =
        new OpenCliHubClient(
            settings("{\"enabled\":false,\"baseUrl\":\"" + baseUrl(server) + "\"}"),
            JsonMapper.builder().build(),
            validCredentialAccess(),
            directHttpClient());

    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertEquals(Reachability.NOT_CHECKED, result.reachability());
    assertEquals(Authentication.NOT_CHECKED, result.authentication());
    assertEquals(BrowserSession.UNKNOWN, result.browserSession());
    assertEquals(List.of(), requestLines);
    assertEquals(0, rejectedCredentials.get());
  }

  /** 测试意图：成功的只读 dates 响应是唯一健康结论来源，且请求恰好是一条带 Bearer 的 GET，绝无生成请求。 */
  @Test
  void readOnlyDatesPathWithBearerIsAcceptedAndEmitsExactlyOneGet() throws IOException {
    HttpServer server = startDatedServer(200, envelope("[\"2026-01-01\"]"), DATES_PATH);
    OpenCliHubClient client = clientFor(server);

    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertEquals(Reachability.REACHABLE, result.reachability());
    assertEquals(Authentication.ACCEPTED, result.authentication());
    assertEquals(BrowserSession.UNKNOWN, result.browserSession());
    assertEquals(List.of("GET " + DATES_PATH), requestLines);
    assertEquals(List.of("Bearer " + API_KEY), authorizations);
    assertEquals(List.of(""), requestBodies, "只读探测必须没有请求体");
    assertEquals(List.of(), otherRequests, "不得命中任何其它路径或生成端点");
    // 私有身份只属于执行请求体，探测路径绝不携带它
    assertEquals(0, rejectedCredentials.get());
  }

  /** 测试意图：401/403 是远端确定性拒绝，只作废本次凭据，且只作废一次。 */
  @Test
  void rejectedCredentialReportsReachableAndRejectedAndRejectsOnlyThisCredential()
      throws IOException {
    for (int status : List.of(401, 403)) {
      HttpServer server = startDatedServer(status, "{\"status\":" + status + "}", DATES_PATH);
      OpenCliHubClient client = clientFor(server);

      PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

      assertEquals(Reachability.REACHABLE, result.reachability(), "HTTP " + status);
      assertEquals(Authentication.REJECTED, result.authentication(), "HTTP " + status);
      assertEquals(List.of("GET " + DATES_PATH), requestLines, "HTTP " + status);
      assertEquals(1, rejectedCredentials.get(), "HTTP " + status);
      assertEquals("opencli", rejectedSnapshot.get().pluginId(), "HTTP " + status);
      assertEquals(List.of(), otherRequests);
      resetRecordings();
    }
  }

  /** 测试意图：远端错误与协议不符绝不冒充认证成功或没有凭据，只陈述「已确认收到响应」。 */
  @Test
  void serverErrorAndProtocolViolationsReportReachableWithoutAuthenticationClaim()
      throws IOException {
    record Case(String description, int status, String contentType, String body) {}
    List<Case> cases =
        List.of(
            new Case("server error", 503, "application/json", "{\"status\":503}"),
            new Case("malformed json", 200, "application/json", "not-json"),
            new Case("data not an array", 200, "application/json", envelope("{\"a\":1}")),
            new Case("non json content type", 200, "text/plain", envelope("[]")));

    for (Case testCase : cases) {
      HttpServer server = startServer(testCase.status(), testCase.contentType(), testCase.body());
      OpenCliHubClient client = clientFor(server);

      PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

      assertEquals(Reachability.REACHABLE, result.reachability(), testCase.description());
      assertEquals(
          Authentication.NOT_CHECKED,
          result.authentication(),
          testCase.description() + " 不得被说成认证失败或没有凭据");
      assertEquals(List.of(), otherRequests, testCase.description());
      resetRecordings();
    }
  }

  /** 测试意图：重定向绝不跟随，因此只存在一条原始请求，且不声称认证成功。 */
  @Test
  void redirectIsNeverFollowedAndNeverClaimsAuthentication() throws IOException {
    HttpServer target = startDatedServer(200, envelope("[]"), DATES_PATH);
    HttpServer redirecting =
        startServer(
            302,
            "application/json",
            "{\"status\":302}",
            exchange -> {
              exchange.getResponseHeaders().set("Location", baseUrl(target) + DATES_PATH);
              exchange.sendResponseHeaders(302, -1);
            });
    OpenCliHubClient client = clientFor(redirecting);

    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertEquals(Reachability.REACHABLE, result.reachability());
    assertEquals(Authentication.NOT_CHECKED, result.authentication());
    assertEquals(List.of("GET " + DATES_PATH), requestLines);
    assertEquals(List.of(), otherRequests, "不得跟随重定向到目标 Hub");
  }

  /** 测试意图：超过本地正文预算的响应只证明收到了响应，绝不冒充认证成功。 */
  @Test
  void oversizedResponseNeverClaimsAuthentication() throws IOException {
    String oversized =
        "{\"status\":200,\"code\":\"OK\",\"success\":true,\"data\":["
            + "\"x\",".repeat(200_000)
            + "\"\"]}";
    HttpServer server = startDatedServer(200, oversized, DATES_PATH);
    OpenCliHubClient client = clientFor(server);

    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertEquals(Reachability.REACHABLE, result.reachability());
    assertEquals(Authentication.NOT_CHECKED, result.authentication());
  }

  /** 测试意图：慢正文被本地 deadline 打断，但已收到响应头，所以结论仍是可达且不声称认证。 */
  @Test
  void slowBodyIsBoundedAndStillReportsReachableWithoutAuthenticationClaim() throws IOException {
    HttpServer server =
        startServer(
            200,
            "application/json",
            null,
            exchange -> {
              exchange.sendResponseHeaders(200, 1024);
              try (OutputStream body = exchange.getResponseBody()) {
                body.write("{\"status\":200".getBytes(StandardCharsets.UTF_8));
                body.flush();
                sleep(3_000);
              }
            });
    OpenCliHubClient client =
        new OpenCliHubClient(
            settings(
                "{\"enabled\":true,\"baseUrl\":\""
                    + baseUrl(server)
                    + "\",\"requestTimeoutMillis\":1000}"),
            JsonMapper.builder().build(),
            validCredentialAccess(),
            directHttpClient());

    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertEquals(Reachability.REACHABLE, result.reachability());
    assertEquals(Authentication.NOT_CHECKED, result.authentication());
  }

  /** 测试意图：可证明连接从未建立时只陈述不可达，绝不声称认证失败或没有凭据。 */
  @Test
  void connectionRefusedReportsUnreachableWithoutAuthenticationClaim() throws IOException {
    int freePort;
    try (ServerSocket socket = new ServerSocket(0)) {
      freePort = socket.getLocalPort();
    }
    OpenCliHubClient client =
        new OpenCliHubClient(
            settings("{\"enabled\":true,\"baseUrl\":\"http://127.0.0.1:" + freePort + "\"}"),
            JsonMapper.builder().build(),
            validCredentialAccess(),
            directHttpClient());

    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertEquals(Reachability.UNREACHABLE, result.reachability());
    assertEquals(Authentication.NOT_CHECKED, result.authentication());
    assertEquals(0, rejectedCredentials.get());
  }

  /** 测试意图：本地没有可用凭据时才允许 UNAVAILABLE，且绝不发出请求。 */
  @Test
  void missingLocalCredentialReportsNotCheckedAndUnavailable() throws IOException {
    HttpServer server = startDatedServer(200, envelope("[]"), DATES_PATH);
    PluginCredentialAccess unavailable =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            throw new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.NOT_CONNECTED, "no credential");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            throw new AssertionError("no request may be sent without a credential");
          }
        };
    OpenCliHubClient client =
        new OpenCliHubClient(
            settings("{\"enabled\":true,\"baseUrl\":\"" + baseUrl(server) + "\"}"),
            JsonMapper.builder().build(),
            unavailable,
            directHttpClient());

    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertEquals(Reachability.NOT_CHECKED, result.reachability());
    assertEquals(Authentication.UNAVAILABLE, result.authentication());
    assertEquals(List.of(), requestLines);
    assertEquals(List.of(), otherRequests);
  }

  /** 测试意图：类型化分类是唯一的分类来源，每个 kind 都投影为契约声明的维度。 */
  @Test
  void kindClassificationIsProjectedWithoutReadingMessages() {
    record Case(Kind kind, Reachability reachability, Authentication authentication) {}
    List<Case> cases =
        List.of(
            new Case(Kind.DISABLED, Reachability.NOT_CHECKED, Authentication.NOT_CHECKED),
            new Case(
                Kind.CREDENTIAL_UNAVAILABLE, Reachability.NOT_CHECKED, Authentication.UNAVAILABLE),
            new Case(Kind.AUTH_REJECTED, Reachability.REACHABLE, Authentication.REJECTED),
            new Case(Kind.HTTP_STATUS, Reachability.REACHABLE, Authentication.NOT_CHECKED),
            new Case(Kind.PROTOCOL, Reachability.REACHABLE, Authentication.NOT_CHECKED),
            new Case(Kind.TRANSPORT, Reachability.UNREACHABLE, Authentication.NOT_CHECKED),
            new Case(Kind.INDETERMINATE, Reachability.INDETERMINATE, Authentication.NOT_CHECKED));

    for (Case testCase : cases) {
      OpenCliHubClient client = mock(OpenCliHubClient.class);
      doThrow(new OpenCliHubException(testCase.kind(), "irrelevant free-form message"))
          .when(client)
          .probeConnection();

      PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

      assertEquals(testCase.reachability(), result.reachability(), testCase.kind().name());
      assertEquals(testCase.authentication(), result.authentication(), testCase.kind().name());
      assertEquals(BrowserSession.UNKNOWN, result.browserSession(), testCase.kind().name());
    }
  }

  /** 测试意图：未定义运行时失败与进程级 Error 都不被吞成健康结果，只有已定义类型化失败才被投影。 */
  @Test
  void unexpectedFailuresAreNotDowngradedToAHealthResult() {
    OpenCliHubClient broken = mock(OpenCliHubClient.class);
    doThrow(new IllegalStateException("unexpected")).when(broken).probeConnection();
    assertThrows(IllegalStateException.class, () -> new OpenCliConnectionCheck(broken).check());

    OpenCliHubClient fatal = mock(OpenCliHubClient.class);
    doThrow(new AssertionError("fatal")).when(fatal).probeConnection();
    assertThrows(AssertionError.class, () -> new OpenCliConnectionCheck(fatal).check());
  }

  /** 测试意图：真实 client 的未知凭据解析失败绝不被投影成维度，check 直接上抛且一条 HTTP 都不发。 */
  @Test
  void unknownCredentialResolutionFailureIsNotProjectedAsAHealthResult() throws IOException {
    HttpServer server = startDatedServer(200, envelope("[]"), DATES_PATH);
    PluginCredentialAccess failing =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            throw new IllegalStateException("credential store exploded");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };
    OpenCliHubClient client =
        new OpenCliHubClient(
            settings("{\"enabled\":true,\"baseUrl\":\"" + baseUrl(server) + "\"}"),
            JsonMapper.builder().build(),
            failing,
            directHttpClient());

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> new OpenCliConnectionCheck(client).check());

    assertEquals("OpenCLI Hub credential resolution failed", failure.getMessage());
    assertNull(failure.getCause(), "原始失败不得作为 cause 泄漏");
    assertEquals(List.of(), requestLines, "凭据解析失败绝不触网");
    assertEquals(List.of(), otherRequests);
  }

  /** 测试意图：中断被保留而不是被吞掉，结论保持无法证明，绝不冒充不可达或健康。 */
  @Test
  void interruptIsPreservedAndNeverClaimedAsAHealthResult() throws IOException {
    HttpServer server = startDatedServer(200, envelope("[]"), DATES_PATH);
    OpenCliHubClient client = clientFor(server);

    Thread.currentThread().interrupt();
    PluginConnectionCheckResult result = new OpenCliConnectionCheck(client).check();

    assertTrue(Thread.currentThread().isInterrupted(), "中断标志必须保留");
    Thread.interrupted();
    assertEquals(Reachability.INDETERMINATE, result.reachability());
    assertEquals(Authentication.NOT_CHECKED, result.authentication());
    assertEquals(List.of(), requestLines, "中断后不得再发出探测请求");
  }

  private OpenCliHubClient clientFor(HttpServer server) {
    return new OpenCliHubClient(
        settings("{\"enabled\":true,\"baseUrl\":\"" + baseUrl(server) + "\"}"),
        JsonMapper.builder().build(),
        validCredentialAccess(),
        directHttpClient());
  }

  /** 隔离组合根：直连 HttpClient，不安装全局代理，也不依赖 JVM 默认 selector。 */
  private static HttpClient directHttpClient() {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofMillis(5_000))
        .build();
  }

  private static OpenCliSettings settings(String valuesJson) {
    return OpenCliTestSettings.parse(valuesJson);
  }

  private PluginCredentialAccess validCredentialAccess() {
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(
            "opencli",
            UUID.randomUUID(),
            "context",
            null,
            null,
            1L,
            new byte[0],
            "{\"apiKey\":\"" + API_KEY + "\",\"instanceId\":\"" + INSTANCE_ID + "\"}");
    return new PluginCredentialAccess() {
      @Override
      public PluginCredentialSnapshot resolve() {
        return snapshot;
      }

      @Override
      public boolean rejectUsed(PluginCredentialSnapshot target, String error) {
        rejectedCredentials.incrementAndGet();
        rejectedSnapshot.set(target);
        return true;
      }
    };
  }

  private HttpServer startDatedServer(int status, String body, String path) throws IOException {
    return startServer(status, "application/json", body, null, path);
  }

  private HttpServer startServer(int status, String contentType, String body) throws IOException {
    return startServer(status, contentType, body, null, DATES_PATH);
  }

  private HttpServer startServer(int status, String contentType, String body, ExchangeHandler extra)
      throws IOException {
    return startServer(status, contentType, body, extra, DATES_PATH);
  }

  /** 记录真实请求，并为同一服务器注册兜底路径：任何非只读路径都会进入 otherRequests。 */
  private HttpServer startServer(
      int status, String contentType, String body, ExchangeHandler extra, String path)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          requestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          requestBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (extra != null) {
            extra.handle(exchange);
            exchange.close();
            return;
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.createContext(
        "/",
        exchange -> {
          otherRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    servers.add(server);
    return server;
  }

  private static String baseUrl(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static String envelope(String data) {
    return "{\"status\":200,\"code\":\"OK\",\"success\":true,\"data\":" + data + "}";
  }

  private void resetRecordings() {
    requestLines.clear();
    authorizations.clear();
    requestBodies.clear();
    otherRequests.clear();
    rejectedCredentials.set(0);
    rejectedSnapshot.set(null);
  }

  /** 允许抛出 IOException 的交换处理器：真实 HttpServer handler 的签名。 */
  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
