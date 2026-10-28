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
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxHeaders;
import fun.fengwk.kkstudio.ide.mux.MuxOpen;
import fun.fengwk.kkstudio.ide.mux.MuxReceivedData;
import fun.fengwk.kkstudio.ide.mux.MuxReset;
import fun.fengwk.kkstudio.ide.mux.MuxStream;
import fun.fengwk.kkstudio.ide.mux.MuxStreamListener;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
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
 * <p><b>两类用例，覆盖范围不同，不可互相替代：</b>
 *
 * <ul>
 *   <li><b>浏览器入口用例</b>（{@code servesRealManagedCodeServerViewsThroughTheProductionDataPlane}、{@code
 *       servesTheManagedViewThroughTheRealBrowserEntryAndDataPlane}）：走真实浏览器协议——事件通道 {@code
 *       /api/events/v2} 登记打开意图、REST {@code /api/environments/{id}/ide/views} 领取视图、一次性 bootstrap 票据换
 *       view 级 cookie、view 基址取 workbench HTML 与静态/worker 资源、浏览器 WebSocket 升级。这只验证浏览器协议入口，不运行真实工作台。
 *   <li><b>数据面用例</b>（{@code streamsRealCodeServerContentOverTheReusedMuxDataConnection}、{@code
 *       servesTwoManagedViewsOverOneReusedDataConnection}）：直接经产品自己的数据连接注册表打开 mux 逻辑流，覆盖真实 DATA_BIND
 *       回连、真实 mux 编解码、Daemon 真实视图解析与真实受管 code-server。<b>它们不经过浏览器会话与 cookie
 *       授权层，因此不能当作浏览器入口已验收的证据。</b>
 * </ul>
 *
 * <p><b>生命周期用例</b>：{@code closesTheManagedViewThroughTheRestApi}（REST 关闭）、 {@code
 * detachFreezesTheOldEpochAndResumeIssuesANewBinding}（detach 冻结旧 epoch + resume 换代 + 旧 cookie/旧
 * epoch 失效）、{@code invalidatesTheOldBindingAfterTheManagedCodeServerCrashes}（受管 code-server
 * 崩溃后旧绑定判为 VIEW_GONE，新视图在替代实例上恢复服务）。
 *
 * <p><b>opt-in：</b>本类只在显式给出已构建 code-server 路径时运行（{@code
 * -Dkk-studio.it.ide.code-server-release=<release 目录>}）。默认测试运行不含本类，因此既有测试行为不变；一旦显式指定路径而该路径缺失或不是可用的
 * code-server 发行目录，测试<b>失败</b>而不是跳过，避免把未验收当成已验收。 真实 Chromium 用例还需 {@code
 * -Dkk-studio.it.ide.playwright-module=<已安装的 @playwright/test 包目录>}。
 *
 * <p><b>隔离与凭据：</b>每个用例自行创建 Environment 与 Daemon；Daemon 发行根、数据目录、受管工作目录全部是夹具自有的临时目录
 * （不使用用户主目录）。注册凭据只写进私有 {@code daemon.token}（0600）；进程日志落在工作区 {@code records} 目录
 * （0700/0600）。测试失败消息不包含凭据、票据或 cookie 值。
 */
@EnabledIfSystemProperty(
    named = IdeProductionChainAcceptanceTest.CODE_SERVER_RELEASE_PROPERTY,
    matches = ".*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdeProductionChainAcceptanceTest extends WebPostgresTestSupport {

  /** 已构建 code-server 发行目录；显式提供时缺失即失败。 */
  static final String CODE_SERVER_RELEASE_PROPERTY = "kk-studio.it.ide.code-server-release";

  /** Node 24 可执行文件；默认使用工作区约定的本地安装。 */
  static final String NODE_BINARY_PROPERTY = "kk-studio.it.ide.node";

  /** 显式指定已安装的 Playwright 包目录，才运行真实 Chromium 用例。 */
  static final String PLAYWRIGHT_MODULE_PROPERTY = "kk-studio.it.ide.playwright-module";

  private static final String DEFAULT_NODE_BINARY = "/home/fengwk/.local/node-v24/bin/node";

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

  @Autowired private IdeGatewayProperties properties;

  @Autowired private EnvironmentDaemonServer daemonServer;

  @Autowired private IdeBootstrapTicketService bootstrapTickets;

  @Autowired private IdeDataConnectionRegistry dataConnections;

  @Autowired
  @Qualifier("localEnvironmentIdeService")
  private EnvironmentIdeServiceImpl localIdeService;

  private final OkHttpClient browser = new OkHttpClient.Builder().followRedirects(false).build();

  private ProductionDaemonFixture daemon;

  private BrowserDriver browserDriver;

  @AfterEach
  void tearDown() {
    RuntimeException failure = null;
    try {
      if (browserDriver != null) {
        try {
          browserDriver.close();
        } catch (RuntimeException error) {
          failure = error;
          if (daemon != null) {
            daemon.preserveWorkspace();
          }
        }
      }
      try {
        if (daemon != null) {
          daemon.close();
          daemon = null;
        }
      } catch (RuntimeException error) {
        if (failure == null) {
          failure = error;
        } else {
          failure.addSuppressed(error);
        }
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
   * 完整链路（浏览器入口口径）：注册意图 → REST 领取视图 → bootstrap → workbench HTML → 静态资源 → Web Worker → 浏览器 WebSocket
   * → 双视图 → 关闭其中一个并确认另一个继续可用 → 关闭剩余视图。
   *
   * <p>两个视图共用同一条复用数据连接与同一个 code-server 进程；关闭使用显式 discard，因为受管工作区的 dirty 判定需要真实编辑器窗口回报。
   */
  @Test
  @Order(1)
  void servesRealManagedCodeServerViewsThroughTheProductionDataPlane() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
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

      String firstCookie = bootstrap(firstEntry);
      String firstHtml = fetchView(environmentId, firstView, firstCookie, "/");
      assertTrue(
          firstHtml.toLowerCase().contains("<html"),
          "the view base must return the code-server workbench HTML");

      String staticAsset =
          fetchView(
              environmentId, firstView, firstCookie, "/_static/src/browser/media/favicon.svg");
      assertTrue(
          staticAsset.contains("<svg"), "the view base must serve the code-server static tree");

      // Web Worker：workbench 的 service worker 脚本必须能被同一数据面取回。
      String worker =
          fetchView(environmentId, firstView, firstCookie, "/_static/out/browser/serviceWorker.js");
      assertFalse(worker.isBlank(), "the view base must serve the code-server worker script");

      WebSocketSession socket = openBrowserWebSocket(environmentId, firstView, firstCookie);
      socket.awaitTerminalOutcome();

      // 第二个视图：同一 Environment、同一数据连接、同一 code-server 进程。
      UUID secondView = UUID.randomUUID();
      UUID secondClaim = UUID.randomUUID();
      events.registerUsage(
          documentId,
          2L,
          List.of(
              new IntentView(environmentId, firstView, firstClaim, null),
              new IntentView(environmentId, secondView, secondClaim, null)));
      JsonNode second = openView(environmentId, secondView, documentId, identity, secondClaim);
      long secondEpoch = Long.parseLong(second.path("bindingEpoch").asText("0"));
      assertTrue(secondEpoch > 0, "the second OPEN_VIEW must return a binding epoch");
      String secondCookie = bootstrap(second.path("entryUrl").asText());
      String secondHtml = fetchView(environmentId, secondView, secondCookie, "/");
      assertTrue(
          secondHtml.toLowerCase().contains("<html"),
          "the second view must return the code-server workbench HTML");

      // 关闭第一个视图：它自己必须立即失效，另一个视图必须继续可用。
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

  /**
   * 浏览器入口用例：绑定已知时，bootstrap → view 级 cookie → view 基址取回 workbench、静态资源、worker 脚本与真实 WebSocket 会话。
   *
   * <p>本测试不依赖 {@code OPEN_VIEW} 的响应体：视图绑定取自 Daemon 的权威 {@code LIST_VIEWS} 快照，入口票据由产品自身的票据服务签发。
   * 其余环节都是生产实现：网关过滤器 → 浏览器会话 → 数据连接注册表 → 复用 mux 物理连接 → Daemon 视图解析 → 受管 code-server。
   */
  @Test
  @Order(2)
  void servesTheManagedViewThroughTheRealBrowserEntryAndDataPlane() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID claimId = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, claimId, null)));

      // 领取视图：真实下发 IDE_COMMAND，Daemon 启动受管 code-server 并登记视图。
      openView(environmentId, viewId, documentId, identity, claimId);

      EnvironmentIdeViewsDTO snapshot =
          localIdeService.listViews(EnvironmentId.parse(environmentId));
      EnvironmentIdeViewDTO view =
          snapshot.getViews().stream()
              .filter(candidate -> viewId.toString().equals(candidate.getViewId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "the daemon must own the claimed view; snapshot: "
                              + snapshot.getViews()));
      long bindingEpoch = Long.parseLong(view.getBindingEpoch());
      String workspacePath = view.getWorkspacePath();
      assertTrue(bindingEpoch > 0, "the claimed view must carry a positive binding epoch");
      assertFalse(workspacePath.isBlank(), "the claimed view must carry a managed workspace");

      DaemonConnectionIdentity daemonIdentity =
          daemonServer
              .connectionIdentity(EnvironmentId.parse(environmentId))
              .orElseThrow(() -> new AssertionError("the daemon connection must be current"));
      String ticket =
          bootstrapTickets.issue(daemonIdentity, viewId.toString(), bindingEpoch, workspacePath);
      String cookie =
          bootstrap(
              "/environment-ide/"
                  + environmentId
                  + "/"
                  + viewId
                  + "/__ide/bootstrap?token="
                  + ticket);

      String html = fetchView(environmentId, viewId, cookie, "/");
      assertTrue(
          html.toLowerCase().contains("<html"),
          "the browser entry must return the code-server workbench HTML");
      String staticAsset =
          fetchView(environmentId, viewId, cookie, "/_static/src/browser/media/favicon.svg");
      assertTrue(staticAsset.contains("<svg"), "the browser entry must serve the static tree");
      String worker =
          fetchView(environmentId, viewId, cookie, "/_static/out/browser/serviceWorker.js");
      assertFalse(worker.isBlank(), "the browser entry must serve the code-server worker script");
      WebSocketSession socket = openBrowserWebSocket(environmentId, viewId, cookie);
      socket.awaitTerminalOutcome();

      closeView(environmentId, viewId, documentId, bindingEpoch);
    }
  }

  /**
   * 数据面用例：复用 mux 物理连接上的真实 HTTP 逻辑流，取回受管 code-server 的正文。
   *
   * <p>这里使用产品自身的数据连接注册表（浏览器 HTTP 网关使用的同一个对象）打开逻辑流，因此经过的是真实的 DATA_BIND 回连、真实的 mux 编解码、 Daemon
   * 的真实视图解析与真实受管 code-server。
   *
   * <p><b>口径边界：</b>请求由测试直接发给数据连接注册表，未经浏览器会话、cookie 授权与网关过滤器， 因此<b>不是浏览器验收</b>，只能证明「Server → Daemon
   * → 受管 code-server」这一段真实可用。
   */
  @Test
  @Order(3)
  void streamsRealCodeServerContentOverTheReusedMuxDataConnection() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID claimId = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, claimId, null)));

      // 领取视图：真实下发 IDE_COMMAND，Daemon 启动受管 code-server 并登记视图。
      openView(environmentId, viewId, documentId, identity, claimId);

      EnvironmentIdeViewsDTO snapshot =
          localIdeService.listViews(EnvironmentId.parse(environmentId));
      EnvironmentIdeViewDTO view =
          snapshot.getViews().stream()
              .filter(candidate -> viewId.toString().equals(candidate.getViewId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "the daemon must own the claimed view; snapshot: "
                              + snapshot.getViews()));
      long bindingEpoch = Long.parseLong(view.getBindingEpoch());

      MuxResponse html = openMuxHttp(environmentId, viewId, bindingEpoch, "/");
      assertEquals(200, html.status(), "the mux data plane must reach the managed code-server");
      assertTrue(
          html.text().toLowerCase().contains("<html"),
          "the managed code-server must answer with the workbench HTML");

      MuxResponse staticAsset =
          openMuxHttp(
              environmentId, viewId, bindingEpoch, "/_static/src/browser/media/favicon.svg");
      assertEquals(200, staticAsset.status(), "the managed code-server must serve its static tree");
      assertTrue(
          staticAsset.text().contains("<svg"), "the static asset must be the code-server file");

      MuxResponse worker =
          openMuxHttp(environmentId, viewId, bindingEpoch, "/_static/out/browser/serviceWorker.js");
      assertEquals(200, worker.status(), "the mux data plane must serve the code-server worker");
      assertFalse(worker.text().isBlank(), "the worker script must not be empty");
    }
  }

  /**
   * 数据面用例：两个受管视图各自取回真实 code-server 正文。
   *
   * <p>两个视图属于同一个 Environment，经同一个数据连接注册表打开逻辑流（注册表按 Environment 只维持一条复用物理连接）， 并落在同一个受管 code-server
   * 进程上。本用例同样<b>不是浏览器验收</b>，未经浏览器会话与 cookie 授权层。
   */
  @Test
  @Order(4)
  void servesTwoManagedViewsOverOneReusedDataConnection() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
      EventIdentity identity = events.awaitConnected();
      UUID firstView = UUID.randomUUID();
      UUID secondView = UUID.randomUUID();
      UUID firstClaim = UUID.randomUUID();
      UUID secondClaim = UUID.randomUUID();
      events.registerUsage(
          documentId,
          1L,
          List.of(
              new IntentView(environmentId, firstView, firstClaim, null),
              new IntentView(environmentId, secondView, secondClaim, null)));

      long firstEpoch = claimView(environmentId, firstView, documentId, identity, firstClaim);
      long secondEpoch = claimView(environmentId, secondView, documentId, identity, secondClaim);

      MuxResponse first = openMuxHttp(environmentId, firstView, firstEpoch, "/");
      assertEquals(200, first.status(), "the first managed view must be served");
      assertTrue(
          first.text().toLowerCase().contains("<html"),
          "the first managed view must return the code-server workbench HTML");

      MuxResponse second = openMuxHttp(environmentId, secondView, secondEpoch, "/");
      assertEquals(200, second.status(), "the second managed view must be served");
      assertTrue(
          second.text().toLowerCase().contains("<html"),
          "the second managed view must return the code-server workbench HTML");
    }
  }

  /**
   * 关闭受管视图：REST 关闭必须成功。
   *
   * <p>关闭使用显式 discard：受管工作区的 dirty 判定需要真实编辑器窗口回报，测试环境没有浏览器窗口。
   */
  @Test
  @Order(5)
  void closesTheManagedViewThroughTheRestApi() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID claimId = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, claimId, null)));

      long bindingEpoch = claimView(environmentId, viewId, documentId, identity, claimId);
      closeView(environmentId, viewId, documentId, bindingEpoch);
    }
  }

  /**
   * detach 冻结旧 epoch，resume 换代：旧 cookie / 旧 epoch 必须失效，新 epoch 必须可服务。
   *
   * <p>detach 走产品自身的 {@code EnvironmentIdeServiceImpl.detachView}（内部 controller 调用的同一实现），因此下发的是真实
   * DETACH 命令并触发真实本地授权撤销；resume 走公开 REST 的 {@code mode=resume}。
   */
  @Test
  @Order(6)
  void detachFreezesTheOldEpochAndResumeIssuesANewBinding() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID firstClaim = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, firstClaim, null)));

      JsonNode opened = openView(environmentId, viewId, documentId, identity, firstClaim);
      long firstEpoch = Long.parseLong(opened.path("bindingEpoch").asText());
      String firstCookie = bootstrap(opened.path("entryUrl").asText());
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
      String secondCookie = bootstrap(resumed.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, viewId, secondCookie, "/").toLowerCase().contains("<html"),
          "the resumed epoch must serve the workbench");

      Response staleClose = deleteView(environmentId, viewId, documentId, firstEpoch);
      try (staleClose) {
        assertEquals(
            409,
            staleClose.code(),
            "closing with the detached epoch must be rejected: " + staleClose.body().string());
      }
      assertViewRejected(
          environmentId, viewId, firstCookie, "the detached epoch cookie must stay rejected");

      closeView(environmentId, viewId, documentId, secondEpoch);
    }
  }

  /**
   * 受管 code-server 崩溃后：daemon 必须恢复出新的可绑定实例，旧绑定必须判为 VIEW_GONE。
   *
   * <p>崩溃只由夹具强制结束本 Daemon 自己的 code-server 子进程触发；恢复等待以「新 code-server 子进程出现」为证据， 不靠固定睡眠。
   */
  @Test
  @Order(7)
  void invalidatesTheOldBindingAfterTheManagedCodeServerCrashes() throws Exception {
    String environmentId = startEnvironmentAndDaemon();
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewId = UUID.randomUUID();
      UUID firstClaim = UUID.randomUUID();
      events.registerUsage(
          documentId, 1L, List.of(new IntentView(environmentId, viewId, firstClaim, null)));

      JsonNode opened = openView(environmentId, viewId, documentId, identity, firstClaim);
      long firstEpoch = Long.parseLong(opened.path("bindingEpoch").asText());
      String firstCookie = bootstrap(opened.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, viewId, firstCookie, "/").toLowerCase().contains("<html"),
          "the opened view must serve the workbench before the crash");

      daemon.crashCodeServer();
      awaitViewReleased(environmentId, viewId);

      // 崩溃恢复会释放失效视图：旧绑定必须判为 VIEW_GONE，而不是仍可关闭或仍可访问。
      Response staleClose = deleteView(environmentId, viewId, documentId, firstEpoch);
      try (staleClose) {
        String text = staleClose.body().string();
        assertEquals(
            409,
            staleClose.code(),
            "the crashed binding must be gone, not still closable: " + text);
        assertTrue(
            text.contains("IDE view is gone"),
            "the pre-crash binding must be reported as VIEW_GONE: " + text);
      }
      assertViewRejected(
          environmentId, viewId, firstCookie, "the pre-crash cookie must stop authorizing");

      // 新视图必须能在同一个 Daemon 上重新拉起 code-server 并真实服务。
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
      String freshCookie = bootstrap(reopened.path("entryUrl").asText());
      assertTrue(
          fetchView(environmentId, freshView, freshCookie, "/").toLowerCase().contains("<html"),
          "the replacement code-server must serve the new view");
      closeView(environmentId, freshView, documentId, freshEpoch);
    }
  }

  /**
   * 真正的 Chromium 工作台和终端：dirty 拒绝不得中断两个视图，discard 只结束对应 shell。
   *
   * <p>浏览器经 Studio 外部 view 入口访问，不拦截任何网络请求。入口票据只经 stdin 传给驱动，进程参数和诊断均不包含票据。
   */
  @Test
  @Order(8)
  @EnabledIfSystemProperty(named = PLAYWRIGHT_MODULE_PROPERTY, matches = ".*")
  void realWorkbenchKeepsBothTerminalsAfterDirtyRefusalAndClosesOnlyOne() throws Exception {
    Path playwright = Path.of(System.getProperty(PLAYWRIGHT_MODULE_PROPERTY)).toAbsolutePath();
    assertTrue(
        Files.isRegularFile(playwright.resolve("package.json")),
        "the explicitly configured Playwright package directory must exist");
    String environmentId = startEnvironmentAndDaemon();
    Path workspace = daemon.workspaceDirectory();
    Files.writeString(workspace.resolve("draft.txt"), "fixture draft\n", StandardCharsets.UTF_8);
    Path browserTemp = Files.createDirectory(workspace.resolve(".browser-tmp"));
    Files.setPosixFilePermissions(browserTemp, PosixFilePermissions.fromString("rwx------"));
    UUID documentId = UUID.randomUUID();
    try (EventChannel events = EventChannel.connect(browser, webSocketUrl("/api/events/v2"))) {
      EventIdentity identity = events.awaitConnected();
      UUID viewA = UUID.randomUUID();
      UUID viewB = UUID.randomUUID();
      UUID claimA = UUID.randomUUID();
      UUID claimB = UUID.randomUUID();
      events.registerUsage(
          documentId,
          1L,
          List.of(
              new IntentView(environmentId, viewA, claimA, null),
              new IntentView(environmentId, viewB, claimB, null)));
      JsonNode openedA = openView(environmentId, viewA, documentId, identity, claimA);
      JsonNode openedB = openView(environmentId, viewB, documentId, identity, claimB);
      browserDriver = BrowserDriver.start(nodeBinary(), recordsDir());
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
              browserTemp.toString(),
              "views",
              List.of(browserView(viewA, openedA), browserView(viewB, openedB))));

      JsonNode shellEvent = browserDriver.await("shell");
      long pid = shellEvent.path("pid").asLong();
      ProcessHandle shell =
          ProcessHandle.of(pid)
              .filter(ProcessHandle::isAlive)
              .orElseThrow(() -> new AssertionError("view A terminal shell must be alive"));
      assertTrue(
          daemon.descendants().contains(shell),
          "the shell PID from the browser marker must belong to this daemon");
      browserDriver.proceed();
      browserDriver.await("closed");
      shell.onExit().get(30, TimeUnit.SECONDS);
      assertFalse(shell.isAlive(), "discard must actually terminate view A's original shell");
      browserDriver.proceed();
      browserDriver.await("done");
      browserDriver.finish();
    }
  }

  private static Map<String, String> browserView(UUID viewId, JsonNode opened) {
    String epoch = opened.path("bindingEpoch").asText();
    String entryUrl = opened.path("entryUrl").asText();
    assertFalse(epoch.isBlank(), "the browser view must have an epoch");
    assertFalse(entryUrl.isBlank(), "the browser view must have a bootstrap entry");
    return Map.of("viewId", viewId.toString(), "bindingEpoch", epoch, "entryUrl", entryUrl);
  }

  /** 拥有 Node/Chromium 进程并使用不含 URL 的行协议同步 shell 退出证明。 */
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
        String event = line.get(90, TimeUnit.SECONDS);
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
      return value.matches("[A-Za-z0-9 -]{1,60}") ? value : "invalid";
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
      List<String> unresolved = new ArrayList<>();
      try {
        captureDescendants();
      } catch (RuntimeException error) {
        unresolved.add("browser descendant snapshot failed");
      }
      try {
        input.close();
      } catch (IOException error) {
        unresolved.add("stdin close failed");
      }
      try {
        if (!waitFor(process.toHandle(), 10) && !stopHandle(process.toHandle())) {
          unresolved.add("browser driver pid " + process.pid());
        }
      } catch (RuntimeException error) {
        unresolved.add("browser driver exit unconfirmed");
      }
      for (ProcessHandle handle : owned) {
        if (!stopHandle(handle)) {
          unresolved.add("browser descendant pid " + handle.pid());
        }
      }
      try {
        output.close();
      } catch (IOException error) {
        unresolved.add("stdout close failed");
      }
      reader.shutdownNow();
      if (!unresolved.isEmpty()) {
        throw new IllegalStateException(
            "browser cleanup could not be confirmed: " + String.join(", ", unresolved));
      }
    }

    private static boolean stopHandle(ProcessHandle handle) {
      try {
        if (!handle.isAlive()) {
          return true;
        }
        handle.destroy();
        if (waitFor(handle, 10)) {
          return true;
        }
        handle.destroyForcibly();
        return waitFor(handle, 10);
      } catch (RuntimeException error) {
        return false;
      }
    }

    private static boolean waitFor(ProcessHandle handle, long seconds) {
      if (!handle.isAlive()) {
        return true;
      }
      try {
        handle.onExit().get(seconds, TimeUnit.SECONDS);
        return true;
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      } catch (ExecutionException | TimeoutException error) {
        return false;
      }
    }
  }

  /** 存活等待 daemon 完成崩溃收敛：权威视图快照不再包含该 view。 */
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

  /**
   * 领取一个受管视图并返回它的 binding epoch。
   *
   * <p>真实下发 {@code OPEN_VIEW} 命令后，Daemon 会启动受管 code-server 并登记视图；绑定身份取自 Daemon 的权威 {@code
   * LIST_VIEWS} 快照，因此不依赖 {@code OPEN_VIEW} 的响应体。
   */
  private long claimView(
      String environmentId, UUID viewId, UUID documentId, EventIdentity identity, UUID claimId)
      throws IOException {
    openView(environmentId, viewId, documentId, identity, claimId);

    EnvironmentIdeViewsDTO snapshot = localIdeService.listViews(EnvironmentId.parse(environmentId));
    EnvironmentIdeViewDTO view =
        snapshot.getViews().stream()
            .filter(candidate -> viewId.toString().equals(candidate.getViewId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the daemon must own the claimed view; snapshot: " + snapshot.getViews()));
    return Long.parseLong(view.getBindingEpoch());
  }

  /** 经产品数据连接注册表打开一条真实 mux HTTP 逻辑流并收完整个响应。 */
  private MuxResponse openMuxHttp(String environmentId, UUID viewId, long bindingEpoch, String path)
      throws Exception {
    MuxResponse collected = new MuxResponse();
    MuxStream stream =
        dataConnections.open(
            EnvironmentId.parse(environmentId),
            MuxOpen.http(viewId, bindingEpoch, "GET", path, List.of()),
            new MuxStreamListener() {
              @Override
              public void onHeaders(MuxStream opened, MuxHeaders headers) {
                collected.status(headers.status());
                collected.headers().addAll(headers.headers());
              }

              @Override
              public void onData(MuxStream opened, MuxReceivedData data) {
                try {
                  collected.body().write(data.payload());
                } catch (IOException error) {
                  collected.fail(error);
                } finally {
                  data.consumed();
                }
              }

              @Override
              public void onEnd(MuxStream opened) {
                collected.complete();
              }

              @Override
              public void onReset(MuxStream opened, MuxReset reset) {
                collected.fail(
                    new IllegalStateException("the mux stream was reset: " + reset.code()));
              }
            });
    stream.sendEnd().toCompletableFuture().get(30, TimeUnit.SECONDS);
    MuxResponse response = collected.await(Duration.ofSeconds(60));
    stream.complete().toCompletableFuture().get(30, TimeUnit.SECONDS);
    return response;
  }

  /** 一次 mux HTTP 响应的累积结果。 */
  private static final class MuxResponse {

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    private final List<MuxHeader> headers = new ArrayList<>();

    private final CompletableFuture<MuxResponse> done = new CompletableFuture<>();

    private int status;

    int status() {
      return status;
    }

    void status(int value) {
      status = value;
    }

    List<MuxHeader> headers() {
      return headers;
    }

    ByteArrayOutputStream body() {
      return body;
    }

    void complete() {
      done.complete(this);
    }

    void fail(Throwable error) {
      done.completeExceptionally(error);
    }

    MuxResponse await(Duration timeout) throws Exception {
      return done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    String text() {
      return body.toString(StandardCharsets.UTF_8);
    }
  }

  /** 显式路径缺失或不是可用的 code-server 发行目录时直接失败，不跳过。 */
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
    String configured = System.getProperty(NODE_BINARY_PROPERTY, DEFAULT_NODE_BINARY);
    Path binary = Path.of(configured).toAbsolutePath().normalize();
    assertTrue(Files.isExecutable(binary), "the Node 24 binary is not executable: " + binary);
    return binary;
  }

  /** 仓库根：向上找到同时包含 {@code daemon/package.json} 的目录。 */
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

  /** 工作区 records 目录：worktree 的同级目录，0700。 */
  private static Path recordsDir() throws IOException {
    Path records = repoRoot().getParent().resolve("records");
    Files.createDirectories(records);
    Files.setPosixFilePermissions(records, PosixFilePermissions.fromString("rwx------"));
    return records;
  }

  /** 用真实产品 API 创建 Environment 并启动生产 Daemon，返回 Environment id。 */
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

    properties.setDataStreamBaseUrl(baseUrl());
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

  /** 等待真实 Daemon 完成握手并进入 READY。 */
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

  /**
   * 构造 OPEN_VIEW 请求体。
   *
   * <p>directory 一律指向夹具自有临时目录：绝不让受管视图打开用户主目录。
   */
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

  /** 关闭视图并返回原始响应，便于断言拒绝语义。 */
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

  /** 通过产品自身的 detach 用例入口下发 DETACH（内部 controller 调用的同一实现）。 */
  private void detachView(String environmentId, UUID viewId, UUID documentId, long bindingEpoch) {
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    binding.setDocumentId(documentId.toString());
    binding.setBindingEpoch(Long.toString(bindingEpoch));
    localIdeService.detachView(EnvironmentId.parse(environmentId), viewId.toString(), binding);
  }

  /** 断言该 cookie 已不能访问视图数据面（关闭、detach、换代或崩溃后都必须如此）。 */
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

  /** 消费一次性 bootstrap 票据，返回 view 作用域 cookie 值。 */
  private String bootstrap(String entryUrl) throws IOException {
    Response response;
    try {
      response =
          browser.newCall(new Request.Builder().url(baseUrl() + entryUrl).get().build()).execute();
    } catch (IOException error) {
      // 一次性票据在 URL 里：失败信息只保留异常类型，不回显可能带 URL 的原始消息。
      throw new IOException(
          "the bootstrap request failed (" + error.getClass().getSimpleName() + ")");
    }
    try (response) {
      assertEquals(
          303, response.code(), "the bootstrap entry must redirect: " + response.body().string());
      String setCookie = response.header("Set-Cookie");
      assertNotNull(setCookie, "the bootstrap entry must set a session cookie");
      int separator = setCookie.indexOf('=');
      assertTrue(separator > 0, "the bootstrap cookie must carry a value");
      int end = setCookie.indexOf(';', separator);
      return setCookie.substring(separator + 1, end < 0 ? setCookie.length() : end);
    }
  }

  /** 通过 view 基址读取 code-server 正文（workbench HTML 或静态资源）。 */
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
      assertEquals(200, response.code(), "the view data plane must serve " + suffix + ": " + text);
      return text;
    }
  }

  /** 浏览器 WebSocket 直连受管视图：真实升级必须成功，并观察 code-server 的会话响应。 */
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

  /** 一次浏览器 WebSocket 会话：记录升级结果与首个服务端终态。 */
  private static final class WebSocketSession extends WebSocketListener {

    private final CompletableFuture<Void> opened = new CompletableFuture<>();

    private final CompletableFuture<String> terminal = new CompletableFuture<>();

    private WebSocket socket;

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      opened.complete(null);
      // 未带连接令牌的探针：真实 code-server 会给出自己的响应（帧或关闭），而不是被网关挡下。
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

    /** 要求 code-server 对这条真实会话给出响应或主动关闭，而不是静默挂起。 */
    void awaitTerminalOutcome() throws Exception {
      try {
        String outcome = terminal.get(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(outcome, "the code-server websocket must answer or close the session");
      } finally {
        socket.close(1000, "acceptance complete");
      }
    }
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
      // 创建响应体含注册凭据明文：失败信息只报状态码，不回显正文。
      assertEquals(201, response.code(), "creating the environment must succeed");
      return JsonMapper.builder().build().readTree(text);
    }
  }

  /** 浏览器事件通道身份。 */
  private record EventIdentity(String eventNodeId, String connectionId) {}

  /** 一条浏览器 IDE 使用意图。 */
  private record IntentView(String environmentId, UUID viewId, UUID claimId, Long bindingEpoch) {}

  /** 真实浏览器事件通道客户端：登记使用意图并等待服务端 ack。 */
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
      builder.append("{\"version\":2,\"type\":\"ide-use\",\"documentId\":\"").append(documentId);
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
      JsonNode ack = awaitFrame();
      assertEquals(
          "ide-use-accepted",
          ack.path("type").asText(),
          "the ide-use frame must be accepted: " + ack);
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
