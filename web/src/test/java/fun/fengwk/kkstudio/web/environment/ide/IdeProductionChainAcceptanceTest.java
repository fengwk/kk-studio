package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import okhttp3.HttpUrl;
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
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生产链路验收：真实 Server ↔ 生产 Node 24 Daemon ↔ 已构建的受管 code-server。
 *
 * <p>本类自身不注入任何 mock 桩：Studio 是真实 Spring Boot 上下文（临时 PostgreSQL），Daemon 是生产构建的 {@code
 * daemon/dist/main.js} 子进程，code-server 是只读工作区里已构建的真实发行产物。
 *
 * <p><b>覆盖范围：</b>
 *
 * <ul>
 *   <li><b>浏览器入口用例</b>：走真实浏览器协议——事件通道 {@code /ws/events} 登记打开意图、REST {@code
 *       /api/environments/{id}/ide/views} 领取视图、一次性 bootstrap 票据换 view 级 cookie、view 基址取 workbench
 *       HTML 与静态/worker 资源、浏览器 WebSocket 升级。
 *   <li><b>生命周期用例</b>：REST 关闭；resume 换代后旧 cookie/旧 epoch 失效；受管 code-server 崩溃后旧绑定判为
 *       VIEW_GONE，新视图在替代实例上恢复服务。
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

  private BrowserDriver browserDriver;

  private Path browserTempDir;

  private Path testRecordsDir;

  @AfterEach
  void tearDown() {
    RuntimeException failure = null;
    try {
      if (browserDriver != null) {
        try {
          browserDriver.close();
        } catch (RuntimeException error) {
          failure = error;
        } finally {
          browserDriver = null;
        }
      }
      if (browserTempDir != null) {
        try {
          deleteTree(browserTempDir);
        } catch (Exception ignored) {
        } finally {
          browserTempDir = null;
        }
      }
      if (daemon != null) {
        daemon.close();
        daemon = null;
      }
      if (testRecordsDir != null) {
        deleteTree(testRecordsDir);
        testRecordsDir = null;
      }
    } catch (IOException error) {
      throw new IllegalStateException("failed to remove IDE test records", error);
    } catch (RuntimeException error) {
      if (failure == null) {
        failure = error;
      } else {
        failure.addSuppressed(error);
      }
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
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/ws/events"))) {
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
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/ws/events"))) {
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

  /** 同文档恢复换代：旧 cookie / 旧 epoch 必须失效，新 epoch 必须可服务。 */
  @Test
  @Order(3)
  void resumeIssuesANewBindingAndInvalidatesTheOldEpoch() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/ws/events"))) {
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
        assertEquals(409, staleClose.code(), "closing with the old epoch must be rejected");
      }
      assertViewRejected(
          environmentId, viewId, firstCookie, "the old epoch cookie must be rejected");

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
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/ws/events"))) {
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

  /** Chromium 真实工作台验收：首屏、内置终端执行命令、Monaco 文件编辑与保存。 */
  @Test
  @Order(5)
  void drivesRealManagedCodeServerWorkbenchInChromium() throws Exception {
    Path playwright = explicitPlaywrightModule();
    Path node = nodeBinary();

    browserTempDir = Files.createTempDirectory("kk-ide-br-");
    Files.setPosixFilePermissions(browserTempDir, PosixFilePermissions.fromString("rwx------"));

    String environmentId = startEnvironmentAndDaemon();
    Path workspace = daemon.workspaceDirectory();
    Files.writeString(
        workspace.resolve("editable.txt"), "initial text in workspace\n", StandardCharsets.UTF_8);

    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/ws/events"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID claimId = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, claimId, null)));

      JsonNode opened = openView(environmentId, viewId, documentId, identity, claimId);
      long bindingEpoch = Long.parseLong(opened.path("bindingEpoch").asText());
      String entryUrl = opened.path("entryUrl").asText();

      browserDriver = BrowserDriver.start(node, recordsDir());
      browserDriver.send(
          Map.of(
              "playwrightModule",
              playwright.toString(),
              "baseUrl",
              baseUrl(),
              "environmentId",
              environmentId,
              "documentId",
              documentId.toString(),
              "workspaceDirectory",
              workspace.toString(),
              "browserTempDirectory",
              browserTempDir.toString(),
              "entryUrl",
              entryUrl,
              "viewId",
              viewId.toString(),
              "bindingEpoch",
              Long.toString(bindingEpoch)));

      browserDriver.await("workbench-ready");
      browserDriver.await("terminal-success");

      // 宿主文件系统强断言：终端执行输出与退出码
      Path outputPath = workspace.resolve("test-output.txt");
      Path exitCodePath = workspace.resolve("exit-code.txt");
      assertTrue(Files.isRegularFile(outputPath), "test-output.txt must be generated by terminal");
      assertEquals(
          "hello from terminal",
          Files.readString(outputPath, StandardCharsets.UTF_8).trim(),
          "test-output.txt must contain the command output");
      assertTrue(Files.isRegularFile(exitCodePath), "exit-code.txt must be generated by terminal");
      assertEquals(
          "EXIT_CODE=0",
          Files.readString(exitCodePath, StandardCharsets.UTF_8).trim(),
          "exit-code.txt must confirm successful exit code");

      browserDriver.await("file-saved");

      // 宿主文件系统强断言：Monaco UI 编辑并保存的内容
      Path editablePath = workspace.resolve("editable.txt");
      String saved = Files.readString(editablePath, StandardCharsets.UTF_8);
      assertTrue(
          saved.contains("initial text in workspace"),
          "saved file must retain initial text: " + saved);
      assertTrue(
          saved.contains("appended by playwright in monaco editor"),
          "saved file must contain appended text from Monaco UI: " + saved);

      // 保存后的编辑器已无未保存修改：正常关闭必须直接成功，不等待原生对话框也不取消。
      CloseResult savedClose = normalCloseStatus(environmentId, viewId, documentId, bindingEpoch);
      assertTrue(
          savedClose.status() == 200 || savedClose.status() == 204,
          "normal close of the saved view must succeed: "
              + savedClose.status()
              + " "
              + savedClose.body());

      browserDriver.proceed();
      browserDriver.await("done");
      browserDriver.finish();
    }
  }

  /** 不打开视图列目录，再以真实工作台的原生对话框验证取消和正常关闭。 */
  @Test
  @Order(6)
  void listsDirectoriesAndClosesDirtyViewThroughNativeDialog() throws Exception {
    Path playwright = explicitPlaywrightModule();
    Path node = nodeBinary();
    browserTempDir = Files.createTempDirectory("kk-ide-br-");
    String environmentId = startEnvironmentAndDaemon();
    Path workspace = daemon.workspaceDirectory();
    Files.createDirectory(workspace.resolve("z-dir"));
    Files.createDirectory(workspace.resolve("a-dir"));
    Files.createDirectory(workspace.resolve("a-dir").resolve("nested"));
    Files.createSymbolicLink(workspace.resolve("link-dir"), workspace.resolve("a-dir"));
    Files.writeString(workspace.resolve("editable.txt"), "initial text in workspace\n");
    String listingUrl =
        HttpUrl.get(baseUrl() + "/api/environments/" + environmentId + "/ide/directories")
            .newBuilder()
            .addQueryParameter("path", workspace.toString())
            .build()
            .toString();
    try (Response response =
        browser.newCall(new Request.Builder().url(listingUrl).build()).execute()) {
      assertEquals(200, response.code());
      JsonNode listing = JsonMapper.builder().build().readTree(response.body().string());
      assertEquals(workspace.toRealPath().toString(), listing.path("path").asText());
      assertEquals(
          workspace.toRealPath().getParent().toString(), listing.path("parentPath").asText());
      assertFalse(listing.path("truncated").asBoolean());
      assertEquals(2, listing.path("children").size());
      assertEquals("a-dir", listing.path("children").get(0).path("name").asText());
      assertEquals(
          workspace.resolve("a-dir").toRealPath().toString(),
          listing.path("children").get(0).path("path").asText());
      assertEquals("z-dir", listing.path("children").get(1).path("name").asText());
      assertEquals(
          workspace.resolve("z-dir").toRealPath().toString(),
          listing.path("children").get(1).path("path").asText());
    }
    assertTrue(localIdeService.listViews(EnvironmentId.parse(environmentId)).getViews().isEmpty());
    Files.createDirectory(workspace.resolve(".vscode"));
    Files.writeString(workspace.resolve(".vscode/settings.json"), "{\"files.autoSave\":\"off\"}\n");

    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/ws/events"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID claimId = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, claimId, null)));
      JsonNode opened = openView(environmentId, viewId, documentId, identity, claimId);
      long epoch = Long.parseLong(opened.path("bindingEpoch").asText());
      browserDriver = BrowserDriver.start(node, recordsDir());
      browserDriver.send(
          Map.ofEntries(
              Map.entry("playwrightModule", playwright.toString()), Map.entry("baseUrl", baseUrl()),
              Map.entry("environmentId", environmentId),
                  Map.entry("documentId", documentId.toString()),
              Map.entry("workspaceDirectory", workspace.toString()),
                  Map.entry("browserTempDirectory", browserTempDir.toString()),
              Map.entry("entryUrl", opened.path("entryUrl").asText()),
                  Map.entry("viewId", viewId.toString()),
              Map.entry("bindingEpoch", Long.toString(epoch)),
                  Map.entry("scenario", "dirty-close")));
      browserDriver.await("workbench-ready");
      browserDriver.await("editor-ready");
      String binding = "{\"documentId\":\"" + documentId + "\",\"bindingEpoch\":\"" + epoch + "\"}";
      Request mobile =
          new Request.Builder()
              .url(
                  baseUrl()
                      + "/api/environments/"
                      + environmentId
                      + "/ide/views/"
                      + viewId
                      + "/mobile-layout")
              .post(RequestBody.create(binding, JSON))
              .build();
      try (Response response =
          browser
              .newBuilder()
              .readTimeout(90, TimeUnit.SECONDS)
              .build()
              .newCall(mobile)
              .execute()) {
        String text = response.body().string();
        assertTrue(
            response.code() == 200 || response.code() == 204,
            "mobile layout: " + response.code() + " " + text);
      }
      browserDriver.proceed();
      String cookie = browserDriver.await("dirty-ready").path("cookie").asText();
      assertFalse(cookie.isBlank());
      assertEquals(
          "initial text in workspace\n", Files.readString(workspace.resolve("editable.txt")));
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<CloseResult> first =
            executor.submit(() -> normalCloseStatus(environmentId, viewId, documentId, epoch));
        try {
          browserDriver.await("close-prompt");
        } catch (AssertionError failure) {
          if (first.isDone()) {
            throw new AssertionError(
                "close returned before native prompt: " + first.get(), failure);
          }
          throw failure;
        }
        // The production HTTP, control and bridge layers must all outlive the former 20/30s
        // budgets.
        assertThrows(TimeoutException.class, () -> first.get(32, TimeUnit.SECONDS));
        assertTrue(fetchView(environmentId, viewId, cookie, "/").toLowerCase().contains("<html"));
        browserDriver.proceed();
        browserDriver.await("dialog-answered");
        CloseResult cancelled = first.get(90, TimeUnit.SECONDS);
        assertEquals(400, cancelled.status(), "Cancel must reject the close");
        JsonNode problem = JsonMapper.builder().build().readTree(cancelled.body());
        assertEquals("validation", problem.path("code").asText());
        assertEquals("IDE_CLOSE_CANCELLED", problem.path("reason").asText());
        assertTrue(fetchView(environmentId, viewId, cookie, "/").toLowerCase().contains("<html"));
        assertTrue(
            localIdeService.listViews(EnvironmentId.parse(environmentId)).getViews().stream()
                .anyMatch(view -> viewId.toString().equals(view.getViewId())));
        Future<CloseResult> second =
            executor.submit(() -> normalCloseStatus(environmentId, viewId, documentId, epoch));
        browserDriver.await("close-prompt");
        browserDriver.proceed();
        browserDriver.await("dialog-answered");
        int status = second.get(90, TimeUnit.SECONDS).status();
        assertTrue(status == 200 || status == 204, "normal close must succeed: " + status);
        awaitViewReleased(environmentId, viewId);
        assertViewRejected(environmentId, viewId, cookie, "closed view must reject its cookie");
        assertEquals(
            "initial text in workspace\n", Files.readString(workspace.resolve("editable.txt")));
        browserDriver.proceed();
        browserDriver.await("done");
        browserDriver.finish();
      } finally {
        executor.shutdownNow();
      }
    }
  }

  private record CloseResult(int status, String body) {}

  private CloseResult normalCloseStatus(
      String environmentId, UUID viewId, UUID documentId, long epoch) throws IOException {
    String body =
        "{\"documentId\":\""
            + documentId
            + "\",\"bindingEpoch\":\""
            + epoch
            + "\",\"discard\":false}";
    Request request =
        new Request.Builder()
            .url(baseUrl() + "/api/environments/" + environmentId + "/ide/views/" + viewId)
            .delete(RequestBody.create(body, JSON))
            .build();
    try (Response response =
        browser.newBuilder().readTimeout(90, TimeUnit.SECONDS).build().newCall(request).execute()) {
      return new CloseResult(response.code(), response.body().string());
    }
  }

  private String startEnvironmentAndDaemon() throws Exception {
    Path release = explicitCodeServerRelease();
    JsonNode created =
        postJson("/api/environments", "{\"name\":\"ide-acceptance-" + suffix() + "\"}");
    JsonNode card = created;
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
      JsonNode card = getJson("/api/environments/" + environmentId);
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
      return JsonMapper.builder().build().readTree(text);
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

  private Path explicitPlaywrightModule() {
    String configured = System.getProperty(PLAYWRIGHT_MODULE_PROPERTY);
    assumeTrue(
        configured != null && !configured.isBlank(),
        "Playwright acceptance is opt-in: configure -D"
            + PLAYWRIGHT_MODULE_PROPERTY
            + "=<path-to-@playwright/test>");
    Path module = Path.of(configured).toAbsolutePath().normalize();
    assertTrue(
        Files.isDirectory(module) || Files.isRegularFile(module),
        "the configured Playwright module path does not exist: " + module);
    return module;
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

  private Path recordsDir() throws IOException {
    if (testRecordsDir == null) {
      testRecordsDir = Files.createTempDirectory("kk-ide-records-");
      Files.setPosixFilePermissions(testRecordsDir, PosixFilePermissions.fromString("rwx------"));
    }
    return testRecordsDir;
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

  /** 拥有 Node/Chromium 进程并使用不含敏感数据的行协议同步执行状态。 */
  private static final class BrowserDriver implements AutoCloseable {
    private final Process process;
    private final BufferedReader output;
    private final BufferedWriter input;
    private final Path stderrLog;
    private final Set<ProcessHandle> owned = new LinkedHashSet<>();
    private final ExecutorService reader =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "ide-browser-events");
              thread.setDaemon(true);
              return thread;
            });
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String stage = "starting";

    private BrowserDriver(Process process, Path stderrLog) {
      this.process = process;
      this.stderrLog = stderrLog;
      this.output =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      this.input =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    static BrowserDriver start(Path node, Path records) throws IOException {
      Path script =
          repoRoot()
              .resolve(
                  "web/src/test/resources/fun/fengwk/kkstudio/web/environment/ide/ide-production-browser.mjs");
      Path log = Files.createTempFile(records, "ide-browser-", ".err.log");
      Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
      ProcessBuilder builder = new ProcessBuilder(node.toString(), script.toString());
      builder.redirectError(log.toFile());
      builder.environment().put("no_proxy", "127.0.0.1,localhost,::1");
      builder.environment().put("NO_PROXY", "127.0.0.1,localhost,::1");
      return new BrowserDriver(builder.start(), log);
    }

    void send(Object value) throws IOException {
      input.write(mapper.writeValueAsString(value));
      input.newLine();
      input.flush();
    }

    void proceed() throws IOException {
      send(Map.of("continue", true));
    }

    JsonNode await(String expected) throws Exception {
      while (true) {
        Future<String> line = reader.submit(output::readLine);
        String event = line.get(120, TimeUnit.SECONDS);
        captureDescendants();
        if (event == null) {
          throw new AssertionError("browser driver exited during " + stage + "; see " + stderrLog);
        }
        JsonNode frame;
        try {
          frame = mapper.readTree(event);
        } catch (RuntimeException error) {
          throw new AssertionError("invalid browser driver event at " + stage);
        }
        String type = safeLabel(frame.path("event").asText());
        if ("stage".equals(type)) {
          stage = safeLabel(frame.path("stage").asText());
          continue;
        }
        if ("failure".equals(type)) {
          throw new AssertionError(
              "browser driver failed at "
                  + safeLabel(frame.path("stage").asText())
                  + " ("
                  + safeLabel(frame.path("detail").asText())
                  + "); see "
                  + stderrLog);
        }
        assertEquals(expected, type, "unexpected browser driver event at " + stage);
        return frame;
      }
    }

    private static String safeLabel(String value) {
      return value.matches("[A-Za-z0-9_-]{1,60}") ? value : "invalid";
    }

    void finish() throws Exception {
      input.close();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "browser driver must exit after closing");
      assertEquals(0, process.exitValue(), "browser driver must exit cleanly; see " + stderrLog);
    }

    private void captureDescendants() {
      owned.addAll(process.toHandle().descendants().toList());
    }

    @Override
    public void close() {
      try {
        captureDescendants();
      } catch (RuntimeException ignored) {
      }
      try {
        reader.shutdownNow();
      } catch (RuntimeException ignored) {
      }
      try {
        input.close();
      } catch (IOException ignored) {
      }
      try {
        output.close();
      } catch (IOException ignored) {
      }
      process.destroy();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException ignored) {
        process.destroyForcibly();
      }
      for (ProcessHandle handle : owned) {
        try {
          if (handle.isAlive()) {
            handle.destroy();
          }
        } catch (RuntimeException ignored) {
        }
      }
      for (ProcessHandle handle : owned) {
        try {
          if (handle.isAlive()) {
            handle.destroyForcibly();
          }
        } catch (RuntimeException ignored) {
        }
      }
    }
  }

  private static void deleteTree(Path path) throws IOException {
    if (Files.notExists(path)) {
      return;
    }
    try (var stream = Files.walk(path)) {
      stream
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
              });
    }
  }
}
