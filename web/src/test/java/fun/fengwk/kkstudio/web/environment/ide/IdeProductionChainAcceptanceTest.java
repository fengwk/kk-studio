package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 生产链路验收：真实 Server ↔ 生产 Node 24 Daemon ↔ 已构建的受管 code-server。
 *
 * <p>本类自身不注入任何 mock 桩：Studio 是真实 Spring Boot 上下文（临时 PostgreSQL），Daemon 是生产构建的 {@code
 * daemon/dist/main.js} 子进程，code-server 是只读工作区里已构建的真实发行产物。
 *
 * <p><b>覆盖范围：</b>
 *
 * <ul>
 *   <li><b>浏览器入口用例</b>：走真实浏览器协议——事件通道 {@code /api/events} 登记打开意图、REST {@code
 *       /api/environments/{id}/ide/views} 领取视图、一次性 bootstrap 票据换 view 级 cookie、view 基址取 workbench
 *       HTML 与静态/worker 资源、浏览器 WebSocket 升级。
 *   <li><b>生命周期用例</b>：REST 关闭；detach 冻结旧 epoch + resume 换代 + 旧 cookie/旧 epoch 失效；受管 code-server
 *       崩溃后旧绑定判为 VIEW_GONE，新视图在替代实例上恢复服务。
 *   <li><b>真实 Chromium 用例</b>：页面不拦截网络，经 Studio
 *       网关加载真实工作台；在工作台中打开终端，执行一条命令并看到输出；修改文件并保存，在宿主机上断言文件内容已落盘。
 * </ul>
 */
@EnabledIfSystemProperty(
    named = IdeProductionChainAcceptanceTest.CODE_SERVER_RELEASE_PROPERTY,
    matches = ".*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdeProductionChainAcceptanceTest extends WebPostgresTestSupport {

  static final String CODE_SERVER_RELEASE_PROPERTY = "kk-studio.it.ide.code-server-release";
  static final String NODE_BINARY_PROPERTY = "kk-studio.it.ide.node";
  static final String PLAYWRIGHT_MODULE_PROPERTY = "kk-studio.it.ide.playwright-module";

  private static final String CLUSTER_SECRET = "ide-production-chain-acceptance-secret";
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(120);

  @DynamicPropertySource
  static void clusterProperties(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.cluster.secret", () -> CLUSTER_SECRET);
  }

  @LocalServerPort private int port;

  @Autowired private EnvironmentDaemonServer daemonServer;

  @Autowired private IdeBootstrapTicketService bootstrapTickets;

  @Autowired
  @Qualifier("localEnvironmentIdeService")
  private EnvironmentIdeServiceImpl localIdeService;

  private final OkHttpClient browser = new OkHttpClient.Builder().followRedirects(false).build();

  private ProductionDaemonFixture daemon;

  @AfterEach
  void tearDown() {
    RuntimeException failure = null;
    try {
      if (daemon != null) {
        daemon.close();
        daemon = null;
      }
    } catch (RuntimeException error) {
      failure = error;
    } finally {
      browser.dispatcher().executorService().shutdown();
      browser.connectionPool().evictAll();
    }
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * 完整链路（浏览器入口口径）：注册意图 → REST 领取视图 → bootstrap → Path 限定 HttpOnly cookie → workbench HTML → 静态资源 →
   * Web Worker → 浏览器 WebSocket → 双视图 → 关闭其中一个并确认另一个继续可用 → 关闭剩余视图。
   */
  @Test
  @Order(1)
  void servesRealManagedCodeServerViewsThroughTheProductionDataPlane() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events"))) {
      EventIdentity identity = events.awaitConnected();
      UUID firstView = UUID.randomUUID();
      UUID firstClaim = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, firstView, firstClaim, null)));

      JsonNode first = openView(environmentId, firstView, documentId, identity, firstClaim);
      long firstEpoch = Long.parseLong(first.path("bindingEpoch").asText("0"));
      String firstEntry = first.path("entryUrl").asText();
      assertTrue(firstEpoch > 0, "OPEN_VIEW must return a binding epoch");
      assertFalse(firstEntry.isBlank(), "OPEN_VIEW must return a bootstrap entry url");

      String firstCookie = bootstrap(environmentId, firstView, firstEntry);
      String firstHtml = fetchView(environmentId, firstView, firstCookie, "/");
      assertTrue(
          firstHtml.toLowerCase().contains("<html"),
          "the view base must return the code-server workbench HTML");

      String staticAsset =
          fetchView(
              environmentId, firstView, firstCookie, "/_static/src/browser/media/favicon.svg");
      assertTrue(
          staticAsset.contains("<svg"), "the view base must serve the code-server static tree");

      String worker =
          fetchView(environmentId, firstView, firstCookie, "/_static/out/browser/serviceWorker.js");
      assertFalse(worker.isBlank(), "the view base must serve the code-server worker script");

      WebSocketSession socket = openBrowserWebSocket(environmentId, firstView, firstCookie);
      socket.awaitTerminalOutcome();

      // 第二个视图：同一 Environment、同一 Daemon、同一 code-server 进程
      UUID secondView = UUID.randomUUID();
      UUID secondClaim = UUID.randomUUID();
      events.registerUsage(
          documentId,
          2L,
          List.of(
              new IntentView(environmentId, firstView, firstClaim, firstEpoch),
              new IntentView(environmentId, secondView, secondClaim, null)));
      JsonNode second = openView(environmentId, secondView, documentId, identity, secondClaim);
      long secondEpoch = Long.parseLong(second.path("bindingEpoch").asText("0"));
      assertTrue(secondEpoch > 0, "the second OPEN_VIEW must return a binding epoch");
      String secondCookie = bootstrap(environmentId, secondView, second.path("entryUrl").asText());
      String secondHtml = fetchView(environmentId, secondView, secondCookie, "/");
      assertTrue(
          secondHtml.toLowerCase().contains("<html"),
          "the second view must return the code-server workbench HTML");

      // 关闭第一个视图：第一个视图必须立即失效，第二个视图必须继续可用
      closeView(environmentId, firstView, documentId, firstEpoch);
      assertViewRejected(environmentId, firstView, firstCookie, "a closed view must stop serving");
      String survivingSecond = fetchView(environmentId, secondView, secondCookie, "/");
      assertTrue(
          survivingSecond.toLowerCase().contains("<html"),
          "closing one view must not take the other view down");

      closeView(environmentId, secondView, documentId, secondEpoch);
      assertViewRejected(
          environmentId, secondView, secondCookie, "the second closed view must stop serving");
    }
  }

  /** 关闭受管视图：REST 关闭必须成功。 */
  @Test
  @Order(2)
  void closesTheManagedViewThroughTheRestApi() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID claimId = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, claimId, null)));

      JsonNode opened = openView(environmentId, viewId, documentId, identity, claimId);
      long bindingEpoch = Long.parseLong(opened.path("bindingEpoch").asText());
      String cookie = bootstrap(environmentId, viewId, opened.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, viewId, cookie, "/").toLowerCase().contains("<html"),
          "the opened view must serve the workbench before close");

      closeView(environmentId, viewId, documentId, bindingEpoch);
      assertViewRejected(
          environmentId, viewId, cookie, "the closed view must reject further requests");

      boolean existsInSnapshot =
          localIdeService.listViews(EnvironmentId.parse(environmentId)).getViews().stream()
              .anyMatch(view -> viewId.toString().equals(view.getViewId()));
      assertFalse(
          existsInSnapshot, "the authoritative snapshot must no longer include the closed view");
    }
  }

  /** detach 冻结旧 epoch，resume 换代：旧 cookie / 旧 epoch 必须失效，新 epoch 必须可服务。 */
  @Test
  @Order(3)
  void detachFreezesTheOldEpochAndResumeIssuesANewBinding() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID firstClaim = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, firstClaim, null)));

      JsonNode opened = openView(environmentId, viewId, documentId, identity, firstClaim);
      long firstEpoch = Long.parseLong(opened.path("bindingEpoch").asText());
      String firstCookie = bootstrap(environmentId, viewId, opened.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, viewId, firstCookie, "/").toLowerCase().contains("<html"),
          "the freshly opened view must serve the workbench");

      detachView(environmentId, viewId, documentId, firstEpoch);
      assertViewRejected(
          environmentId, viewId, firstCookie, "a detached epoch must stop authorizing its cookie");

      UUID secondClaim = UUID.randomUUID();
      events.registerUsage(
          documentId, 2L, List.of(new IntentView(environmentId, viewId, secondClaim, null)));
      JsonNode resumed =
          openView(environmentId, viewId, documentId, identity, secondClaim, "resume");
      long secondEpoch = Long.parseLong(resumed.path("bindingEpoch").asText());
      assertTrue(
          secondEpoch > firstEpoch,
          "resume must allocate a newer binding epoch (" + firstEpoch + " -> " + secondEpoch + ")");
      String secondCookie = bootstrap(environmentId, viewId, resumed.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, viewId, secondCookie, "/").toLowerCase().contains("<html"),
          "the resumed epoch must serve the workbench");

      Response staleClose = deleteView(environmentId, viewId, documentId, firstEpoch);
      try (staleClose) {
        assertEquals(409, staleClose.code(), "closing with the detached epoch must be rejected");
      }
      assertViewRejected(
          environmentId, viewId, firstCookie, "the detached epoch cookie must stay rejected");

      closeView(environmentId, viewId, documentId, secondEpoch);
      assertViewRejected(
          environmentId, viewId, secondCookie, "the closed resumed view must stop serving");
    }
  }

  /** 受管 code-server 崩溃后：daemon 必须恢复出新的可绑定实例，旧绑定必须判为 VIEW_GONE。 */
  @Test
  @Order(4)
  void invalidatesTheOldBindingAfterTheManagedCodeServerCrashes() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID firstClaim = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, firstClaim, null)));

      JsonNode opened = openView(environmentId, viewId, documentId, identity, firstClaim);
      long firstEpoch = Long.parseLong(opened.path("bindingEpoch").asText());
      String firstCookie = bootstrap(environmentId, viewId, opened.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, viewId, firstCookie, "/").toLowerCase().contains("<html"),
          "the opened view must serve the workbench before the crash");

      daemon.crashCodeServer();
      awaitViewReleased(environmentId, viewId);

      Response staleClose = deleteView(environmentId, viewId, documentId, firstEpoch);
      try (staleClose) {
        String text = staleClose.body().string();
        assertEquals(
            409, staleClose.code(), "the crashed binding must be gone, not still closable");
        assertTrue(
            text.contains("IDE view is gone"),
            "the pre-crash binding must be reported as VIEW_GONE");
      }
      assertViewRejected(
          environmentId, viewId, firstCookie, "the pre-crash cookie must stop authorizing");

      UUID freshView = UUID.randomUUID();
      UUID freshClaim = UUID.randomUUID();
      events.registerUsage(
          documentId, 2L, List.of(new IntentView(environmentId, freshView, freshClaim, null)));
      JsonNode reopened = openView(environmentId, freshView, documentId, identity, freshClaim);
      long freshEpoch = Long.parseLong(reopened.path("bindingEpoch").asText("0"));
      assertTrue(freshEpoch > 0, "a new view after the crash must carry a binding epoch");
      assertTrue(
          daemon.hasCodeServer(),
          "the daemon must have started a replacement code-server for the new view");
      String freshCookie = bootstrap(environmentId, freshView, reopened.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, freshView, freshCookie, "/").toLowerCase().contains("<html"),
          "the replacement code-server must serve the new view");
      closeView(environmentId, freshView, documentId, freshEpoch);
    }
  }

  private String startEnvironmentAndDaemon() throws Exception {
    Path release = explicitCodeServerRelease();
    JsonNode created =
        postJson("/api/environments", "{\"name\":\"ide-acceptance-" + suffix() + "\"}");
    JsonNode card = created.path("data");
    String environmentId = card.path("id").asText();
    String registrationToken = card.path("registrationToken").asText();
    assertFalse(environmentId.isBlank(), "environment creation must return an id");
    assertFalse(
        registrationToken.isBlank(), "environment creation must return a registration token");

    daemon =
        ProductionDaemonFixture.start(
            repoRoot(), release, baseUrl(), registrationToken, recordsDir(), nodeBinary());
    awaitReady(environmentId);
    return environmentId;
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + port;
  }

  private String webSocketUrl(String path) {
    return "ws://127.0.0.1:" + port + path;
  }

  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private void awaitReady(String environmentId) throws Exception {
    long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
    String lastStatus = "unknown";
    while (System.nanoTime() < deadline) {
      assertTrue(
          daemon.alive(), "the daemon process exited before READY; see " + daemon.stderrLog());
      JsonNode card = getJson("/api/environments/" + environmentId).path("data");
      lastStatus = card.path("status").asText("unknown");
      if ("READY".equals(lastStatus)) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError(
        "the daemon did not reach READY (last status: "
            + lastStatus
            + "); daemon logs: "
            + daemon.stdoutLog()
            + " / "
            + daemon.stderrLog());
  }

  private String openViewBody(
      String environmentId,
      UUID viewId,
      UUID documentId,
      EventIdentity identity,
      UUID claimId,
      String mode)
      throws IOException {
    return "{\"viewId\":\""
        + viewId
        + "\",\"documentId\":\""
        + documentId
        + "\",\"eventNodeId\":\""
        + identity.eventNodeId()
        + "\",\"connectionId\":\""
        + identity.connectionId()
        + "\",\"claimId\":\""
        + claimId
        + "\",\"mode\":\""
        + mode
        + "\",\"directory\":\""
        + daemon.workspaceDirectory()
        + "\"}";
  }

  private JsonNode openView(
      String environmentId, UUID viewId, UUID documentId, EventIdentity identity, UUID claimId)
      throws IOException {
    return openView(environmentId, viewId, documentId, identity, claimId, "create");
  }

  private JsonNode openView(
      String environmentId,
      UUID viewId,
      UUID documentId,
      EventIdentity identity,
      UUID claimId,
      String mode)
      throws IOException {
    Response response = postOpenView(environmentId, viewId, documentId, identity, claimId, mode);
    try (response) {
      String text = response.body().string();
      assertEquals(200, response.code(), "OPEN_VIEW (" + mode + ") must succeed");
      return JsonMapper.builder().build().readTree(text).path("data");
    }
  }

  private Response postOpenView(
      String environmentId,
      UUID viewId,
      UUID documentId,
      EventIdentity identity,
      UUID claimId,
      String mode)
      throws IOException {
    return browser
        .newCall(
            new Request.Builder()
                .url(baseUrl() + "/api/environments/" + environmentId + "/ide/views")
                .post(
                    RequestBody.create(
                        openViewBody(environmentId, viewId, documentId, identity, claimId, mode),
                        JSON))
                .build())
        .execute();
  }

  private void closeView(String environmentId, UUID viewId, UUID documentId, long bindingEpoch)
      throws IOException {
    String body =
        "{\"documentId\":\""
            + documentId
            + "\",\"bindingEpoch\":\""
            + bindingEpoch
            + "\",\"discard\":true}";
    Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(baseUrl() + "/api/environments/" + environmentId + "/ide/views/" + viewId)
                    .delete(RequestBody.create(body, JSON))
                    .build())
            .execute();
    try (response) {
      String text = response.body().string();
      assertTrue(
          response.code() == 200 || response.code() == 204,
          "closing the view must succeed (status " + response.code() + "): " + text);
    }
  }

  private Response deleteView(String environmentId, UUID viewId, UUID documentId, long bindingEpoch)
      throws IOException {
    String body =
        "{\"documentId\":\""
            + documentId
            + "\",\"bindingEpoch\":\""
            + bindingEpoch
            + "\",\"discard\":true}";
    return browser
        .newCall(
            new Request.Builder()
                .url(baseUrl() + "/api/environments/" + environmentId + "/ide/views/" + viewId)
                .delete(RequestBody.create(body, JSON))
                .build())
        .execute();
  }

  private void detachView(String environmentId, UUID viewId, UUID documentId, long bindingEpoch) {
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    binding.setDocumentId(documentId.toString());
    binding.setBindingEpoch(Long.toString(bindingEpoch));
    localIdeService.detachView(EnvironmentId.parse(environmentId), viewId.toString(), binding);
  }

  private void awaitViewReleased(String environmentId, UUID viewId) throws Exception {
    long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
    boolean released = false;
    while (System.nanoTime() < deadline) {
      assertTrue(
          daemon.alive(), "the daemon exited during crash recovery; see " + daemon.stderrLog());
      try {
        released =
            localIdeService.listViews(EnvironmentId.parse(environmentId)).getViews().stream()
                .noneMatch(view -> viewId.toString().equals(view.getViewId()));
      } catch (RuntimeException unavailable) {
        released = false;
      }
      if (released) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("the daemon never released the crashed view " + viewId);
  }

  private void assertViewRejected(
      String environmentId, UUID viewId, String cookieValue, String message) throws IOException {
    Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(baseUrl() + "/environment-ide/" + environmentId + "/" + viewId + "/")
                    .header("Cookie", "kk_ide_session=" + cookieValue)
                    .header("Sec-Fetch-Site", "same-origin")
                    .get()
                    .build())
            .execute();
    try (response) {
      assertTrue(response.code() >= 400, message + " (status " + response.code() + ")");
    }
  }

  /** 消费一次性 bootstrap 票据，断言 Path 限定的 HttpOnly cookie，并返回 cookie 值。 */
  private String bootstrap(String environmentId, UUID viewId, String entryUrl) throws IOException {
    Response response;
    try {
      response =
          browser.newCall(new Request.Builder().url(baseUrl() + entryUrl).get().build()).execute();
    } catch (IOException error) {
      throw new IOException(
          "the bootstrap request failed (" + error.getClass().getSimpleName() + ")");
    }
    try (response) {
      assertEquals(303, response.code(), "the bootstrap entry must return 303 redirect");
      String expectedPath = "/environment-ide/" + environmentId + "/" + viewId + "/";
      String setCookie = response.header("Set-Cookie");
      assertNotNull(setCookie, "the bootstrap entry must set a session cookie");
      assertTrue(setCookie.startsWith("kk_ide_session="), "cookie name must be kk_ide_session");
      assertTrue(
          setCookie.contains("Path=" + expectedPath),
          "the cookie must be path-scoped to " + expectedPath);
      assertTrue(
          setCookie.toLowerCase().contains("httponly"), "the cookie must be marked HttpOnly");

      int separator = setCookie.indexOf('=');
      int end = setCookie.indexOf(';', separator);
      String value = setCookie.substring(separator + 1, end < 0 ? setCookie.length() : end);
      assertFalse(value.isBlank(), "the bootstrap cookie must carry a non-blank value");
      return value;
    }
  }

  private String fetchView(String environmentId, UUID viewId, String cookieValue, String suffix)
      throws IOException {
    Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(baseUrl() + "/environment-ide/" + environmentId + "/" + viewId + suffix)
                    .header("Cookie", "kk_ide_session=" + cookieValue)
                    .header("Sec-Fetch-Site", "same-origin")
                    .get()
                    .build())
            .execute();
    try (response) {
      String text = response.body().string();
      assertEquals(200, response.code(), "the view data plane must serve " + suffix);
      return text;
    }
  }

  private WebSocketSession openBrowserWebSocket(
      String environmentId, UUID viewId, String cookieValue) throws Exception {
    WebSocketSession session = new WebSocketSession();
    session.socket =
        browser.newWebSocket(
            new Request.Builder()
                .url(webSocketUrl("/environment-ide/" + environmentId + "/" + viewId + "/"))
                .header("Cookie", "kk_ide_session=" + cookieValue)
                .header("Origin", baseUrl())
                .build(),
            session);
    try {
      session.opened.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      throw new AssertionError("the browser websocket upgrade must succeed", error.getCause());
    }
    return session;
  }

  private static final class WebSocketSession extends WebSocketListener {
    private final CompletableFuture<Void> opened = new CompletableFuture<>();
    private final CompletableFuture<String> terminal = new CompletableFuture<>();
    private WebSocket socket;

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      opened.complete(null);
      socket.send("{\"type\":\"probe\"}");
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      terminal.complete("message");
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      terminal.complete("binary");
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      terminal.complete("closing:" + code);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      terminal.complete("closed:" + code);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      opened.completeExceptionally(error);
      terminal.completeExceptionally(error);
    }

    void awaitTerminalOutcome() throws Exception {
      try {
        String outcome = terminal.get(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(outcome, "the code-server websocket must answer or close the session");
      } finally {
        socket.close(1000, "acceptance complete");
      }
    }
  }

  private JsonNode getJson(String path) throws IOException {
    Response response =
        browser.newCall(new Request.Builder().url(baseUrl() + path).get().build()).execute();
    try (response) {
      return JsonMapper.builder().build().readTree(response.body().string());
    }
  }

  private JsonNode postJson(String path, String body) throws IOException {
    Response response =
        browser
            .newCall(
                new Request.Builder()
                    .url(baseUrl() + path)
                    .post(RequestBody.create(body, JSON))
                    .build())
            .execute();
    try (response) {
      String text = response.body().string();
      assertEquals(201, response.code(), "creating the environment must succeed");
      return JsonMapper.builder().build().readTree(text);
    }
  }

  private Path explicitCodeServerRelease() {
    String configured = System.getProperty(CODE_SERVER_RELEASE_PROPERTY);
    assertNotNull(configured, "explicit code-server release path is required");
    Path release = Path.of(configured).toAbsolutePath().normalize();
    assertTrue(
        Files.isDirectory(release),
        "the explicitly configured code-server release directory is missing: " + release);
    assertTrue(
        Files.isRegularFile(release.resolve("out").resolve("node").resolve("entry.js")),
        "the configured code-server release has no out/node/entry.js: " + release);
    assertTrue(
        Files.isExecutable(release.resolve("lib").resolve("node")),
        "the configured code-server release has no executable lib/node: " + release);
    return release;
  }

  private Path nodeBinary() {
    String configured = System.getProperty(NODE_BINARY_PROPERTY);
    assertNotNull(
        configured,
        "explicit Node 24 binary path is required: -Dkk-studio.it.ide.node=<absolute-node-path>");
    assertFalse(configured.isBlank(), "the explicitly configured Node 24 binary path is blank");
    Path binary = Path.of(configured).toAbsolutePath().normalize();
    assertTrue(Files.isExecutable(binary), "the Node 24 binary is not executable: " + binary);
    return binary;
  }

  private static Path repoRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("daemon").resolve("package.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("cannot locate the repository root from the module directory");
  }

  private static Path recordsDir() throws IOException {
    Path records = repoRoot().getParent().resolve("records");
    Files.createDirectories(records);
    Files.setPosixFilePermissions(records, PosixFilePermissions.fromString("rwx------"));
    return records;
  }

  private record EventIdentity(String eventNodeId, String connectionId) {}

  private record IntentView(String environmentId, UUID viewId, UUID claimId, Long bindingEpoch) {}

  private static final class EventChannel implements AutoCloseable {
    private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    private WebSocket socket;

    private EventChannel() {}

    static EventChannel connect(OkHttpClient client, String url) throws IOException {
      EventChannel channel = new EventChannel();
      channel.socket =
          client.newWebSocket(
              new Request.Builder().url(url).build(),
              new WebSocketListener() {
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                  channel.frames.add(JsonMapper.builder().build().readTree(text));
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                  channel.frames.add(JsonMapper.builder().build().readTree(bytes.utf8()));
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                  channel.frames.add(
                      JsonMapper.builder()
                          .build()
                          .createObjectNode()
                          .put("type", "failure")
                          .put("message", error.getClass().getSimpleName()));
                }
              });
      return channel;
    }

    EventIdentity awaitConnected() throws InterruptedException {
      JsonNode frame = awaitFrame();
      assertEquals("connected", frame.path("type").asText(), "the first event frame must connect");
      return new EventIdentity(
          frame.path("eventNodeId").asText(), frame.path("connectionId").asText());
    }

    void registerUsage(UUID documentId, long revision, List<IntentView> views)
        throws InterruptedException {
      StringBuilder builder = new StringBuilder();
      builder.append("{\"type\":\"ide-use\",\"documentId\":\"").append(documentId);
      builder.append("\",\"revision\":\"").append(revision).append("\",\"views\":[");
      for (int index = 0; index < views.size(); index++) {
        IntentView view = views.get(index);
        if (index > 0) {
          builder.append(',');
        }
        builder
            .append("{\"environmentId\":\"")
            .append(view.environmentId())
            .append("\",\"viewId\":\"")
            .append(view.viewId())
            .append("\",\"claimId\":\"")
            .append(view.claimId())
            .append('"');
        if (view.bindingEpoch() != null) {
          builder.append(",\"bindingEpoch\":\"").append(view.bindingEpoch()).append('"');
        }
        builder.append('}');
      }
      builder.append("]}");
      assertTrue(socket.send(builder.toString()), "the ide-use frame must be sent");
      JsonNode ack = awaitUsageAck();
      assertEquals(
          "ide-use-accepted",
          ack.path("type").asText(),
          "the ide-use frame must be accepted: " + ack);
      assertEquals(Long.toString(revision), ack.path("revision").asText());
    }

    private JsonNode awaitUsageAck() throws InterruptedException {
      long deadline = System.nanoTime() + FRAME_TIMEOUT.toNanos();
      JsonNode frame;
      do {
        long remaining = deadline - System.nanoTime();
        assertTrue(remaining > 0, "the event channel produced no ide-use acknowledgement");
        frame = frames.poll(remaining, TimeUnit.NANOSECONDS);
        assertNotNull(frame, "the event channel produced no ide-use acknowledgement");
      } while ("heartbeat".equals(frame.path("type").asText()));
      return frame;
    }

    private JsonNode awaitFrame() throws InterruptedException {
      JsonNode frame = frames.poll(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull(frame, "the event channel produced no frame within " + FRAME_TIMEOUT);
      return frame;
    }

    @Override
    public void close() {
      socket.close(1000, "acceptance complete");
    }
  }
}
