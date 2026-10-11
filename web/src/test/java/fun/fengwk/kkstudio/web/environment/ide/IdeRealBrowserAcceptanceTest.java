package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Opt-in 真实单节点整链验收：真实 Java IDE Gateway + 真实 Node Daemon + 冻结 code-server + 真实 Chromium。
 *
 * <p>仅在 {@code KK_STUDIO_IDE_ACCEPTANCE_DIR}（冻结 code-server 解包目录，含 {@code lib/node}）与 {@code
 * KK_STUDIO_PLAYWRIGHT_MODULE_DIR}（{@code @playwright/test} 包目录）同时存在时启用；缺省时该类在加载 Spring 上下文与
 * PostgreSQL 容器 之前即被跳过。
 *
 * <p>链路全部真实、无 mock：Daemon 以真实 {@code node dist/main.js --config <private>} 子进程连接真实 Daemon WebSocket
 * 端点 （HELLO/WELCOME/READY）；视图由真实 {@code EnvironmentIdeService.OPEN_VIEW} 铸造一次性 bootstrap 入口；每个视图由一个
 * Studio 父页面（{@code studio.browser.localhost}，与 IDE host 同 site 但不同 Origin）按生产 {@code
 * IdeOverlayPanel} 的 sandbox/allow 契约嵌入 iframe，浏览器经 {@code *.browser.localhost} host-resolver
 * 规则访问独立 IDE Origin，再经真实数据面到达 code-server；驱动走真实子 Frame，键盘发往所属父 Page。真实终端由 {@code FOCUS_TERMINAL}
 * 建立，并由浏览器 shell 回写真实 OS pid 供收敛断言。
 *
 * <p>真实 random port 只有运行期可知：Origin 模板、gateway 反向流模板与 Daemon {@code studioUrl} 必须使用同一真实端口， 否则
 * bootstrap 的 Location、host-only cookie 与 WS Origin 会与入参 URL 不一致。本机仅验证 Linux + POSIX 权限模型；其它平台显式跳过。
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "KK_STUDIO_IDE_ACCEPTANCE_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KK_STUDIO_PLAYWRIGHT_MODULE_DIR", matches = ".+")
class IdeRealBrowserAcceptanceTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("55555555-5555-4555-8555-555555555555");
  private static final String REGISTRATION_TOKEN = "ide-browser-acceptance-token";
  private static final String VIEW_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String VIEW_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String BROWSER_SUFFIX = ".ide.browser.localhost";
  private static final String SCOPE_DIR_PREFIX = "kk-studio-daemon-process-scope-";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

  /** 验收必须全部通过的浏览器步骤；冲突保存与外部删除同样是强断言，不是记录项。 */
  private static final List<String> REQUIRED_STEPS =
      List.of(
          "workbenchA",
          "workbenchB",
          "editSaveA",
          "externalChangeB",
          "terminalA",
          "terminalB",
          "dirtyChangeDirectoryRejected",
          "conflictSave",
          "externalDelete",
          "dirtyCloseRejected",
          "clearDirtyBuffersNormalUI",
          "closeViewB",
          "closeViewA");

  @DynamicPropertySource
  static void ideOriginTemplate(DynamicPropertyRegistry registry) {
    // 启动期为端口无关模板以免上下文 fail-closed；真实端口在测试体内按 @LocalServerPort 覆盖到同一单例 bean。
    registry.add(
        "kk-studio.harness.ide.origin-template", () -> "http://{environmentId}" + BROWSER_SUFFIX);
  }

  @LocalServerPort private int port;

  @Autowired private EnvironmentIdeService environmentIdeService;
  @Autowired private EnvironmentRegistry registry;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IdeGatewayProperties gatewayProperties;
  @Autowired private IdeOriginProperties originProperties;

  private Path tempRoot;
  private Path workspaceDirectory;
  private Path processTmp;
  private Process daemon;
  private Process playwright;
  private Path daemonLog;
  private Set<String> scopesBefore;
  private boolean completed;
  private final Set<ProcessHandle> helperProcesses = new LinkedHashSet<>();

  @BeforeEach
  void seedEnvironment() {
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version)"
            + " values (?, 'env-ide-browser', ?, 0) on conflict (id) do nothing",
        ENVIRONMENT.value(),
        REGISTRATION_TOKEN);
  }

  @AfterEach
  void cleanUp() {
    boolean daemonStopped = terminateDaemon();
    boolean playwrightStopped = terminatePlaywright();
    boolean helpersStopped = terminateRetainedHelpers();
    retainArtifacts();
    if (tempRoot == null) {
      return;
    }
    if (!daemonStopped || !playwrightStopped || !helpersStopped) {
      // 子进程未确认结束：保留 owner-only 证据并让用例显式失败，绝不只 warn 后当作通过。
      log.error(
          "helper processes did not terminate; kept owner-only temp root {}",
          tempRoot.toAbsolutePath());
      fail("helper processes did not terminate; kept evidence at " + tempRoot.toAbsolutePath());
    }
    if (!completed) {
      log.info("kept failed acceptance evidence in owner-only directory {}", tempRoot);
      return;
    }
    try {
      deleteRecursively(tempRoot);
      tempRoot = null;
    } catch (IOException error) {
      log.warn("could not delete temp root {}: {}", tempRoot.toAbsolutePath(), error.getMessage());
    }
  }

  /** 真实浏览器驱动两个 UUID 视图：workbench 加载、编辑保存、两个真实终端、dirty 拒绝、关闭后逐视图与整体 native 收敛。 */
  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void drivesTwoRealViewsThroughRealDaemonAndBrowser() throws Exception {
    assumeLinuxPosix();
    int realPort = port;
    // Origin 必须携带真实端口：bootstrap entryUrl、303 Location、host-only cookie 与 WS Origin 都以它为准。
    originProperties.setOriginTemplate("http://{environmentId}" + BROWSER_SUFFIX + ":" + realPort);
    gatewayProperties.setStreamUrlTemplate(
        "ws://localhost:" + realPort + IdeGatewayProperties.STREAM_PATH);

    tempRoot = Files.createTempDirectory("kk-ide-browser-acceptance-");
    Files.setPosixFilePermissions(tempRoot, PosixFilePermissions.fromString("rwx------"));
    // 私有临时目录承载 Daemon/Playwright 的 native scope，Java 只扫描自己这个目录，
    // 不与其它并发 agent 的 /tmp scope 互相误判。
    processTmp = tempRoot.resolve("process-tmp");
    Files.createDirectories(processTmp);
    workspaceDirectory = tempRoot.resolve("workspace");
    Files.createDirectories(workspaceDirectory.resolve("subdir"));
    Files.writeString(workspaceDirectory.resolve("alpha.txt"), "alpha-base\n");
    Files.writeString(workspaceDirectory.resolve("beta.txt"), "beta-base\n");
    Files.writeString(workspaceDirectory.resolve("gamma.txt"), "gamma-base\n");

    Path node = resolveNodeBinary();
    Path daemonSource = repositoryRoot().resolve("daemon");
    Path builtDaemon = buildDaemon(daemonSource, node);
    Path stage = stageDistribution(builtDaemon, requireEnv("KK_STUDIO_IDE_ACCEPTANCE_DIR"));

    scopesBefore = scopeDirectories();
    startDaemon(node, stage);
    awaitReady();

    EnvironmentIdeViewDTO viewA = openView(VIEW_A, workspaceDirectory);
    EnvironmentIdeViewDTO viewB = openView(VIEW_B, workspaceDirectory);
    assertNotNull(viewA.getEntryUrl(), "OPEN_VIEW must mint a one-time bootstrap entry");
    assertNotNull(viewB.getEntryUrl(), "OPEN_VIEW must mint a one-time bootstrap entry");
    for (String viewId : List.of(VIEW_A, VIEW_B)) {
      Path workspaceFile = stage.resolve("data/ide/views/" + viewId + ".code-workspace");
      JsonNode workspace = OBJECT_MAPPER.readTree(workspaceFile.toFile());
      assertEquals(
          workspaceDirectory.toRealPath().toString(),
          workspace.path("folders").get(0).path("path").asText(),
          "the managed workspace must contain the requested host directory");
    }
    Path sessionSocket = assertCodeServerIpc(node);

    Path report = tempRoot.resolve("report.json");
    Path screenshots = tempRoot.resolve("screenshots");
    Path playwrightConfig = tempRoot.resolve("playwright-config.json");
    Files.writeString(
        playwrightConfig,
        playwrightConfigJson(viewA, viewB, report, screenshots),
        StandardCharsets.UTF_8);
    // config 含一次性 entryUrl（凭据），显式 owner-only。
    Files.setPosixFilePermissions(playwrightConfig, PosixFilePermissions.fromString("rw-------"));
    runPlaywright(node, playwrightConfig, report);

    JsonNode reportJson = OBJECT_MAPPER.readTree(report.toFile());
    for (String required : REQUIRED_STEPS) {
      assertStep(reportJson, required);
    }
    // 两个 workbench 必须真的是跨 Origin iframe 内加载的真实 workbench；父 Origin 与 IDE Origin 不同可防止驱动退回顶层
    // page.goto 的等价假通过。
    assertEmbeddedView(reportJson, "workbenchA");
    assertEmbeddedView(reportJson, "workbenchB");
    assertTrue(
        reportJson.path("ok").asBoolean(false), "browser run must complete all required steps");

    assertTrue(
        Files.readString(workspaceDirectory.resolve("alpha.txt")).contains("line-from-viewA"),
        "the browser edit in view A must reach the on-disk workspace file");
    assertTrue(
        Files.readString(workspaceDirectory.resolve("beta.txt")).contains("A-side"),
        "the conflict run must have written view A's version of the shared file to disk");
    assertTrue(
        Files.readString(workspaceDirectory.resolve("gamma.txt")).contains("gamma-edit"),
        "saving after the external delete must recreate the file on disk with the editor's content");

    // 终端收敛必须靠真实 OS pid，而不是仅回写 viewId。两个视图都在窗口仍连接时经公开路由关闭。
    long pidA = readTerminalPid("A", VIEW_A);
    long pidB = readTerminalPid("B", VIEW_B);
    awaitPidGone(pidA, Duration.ofSeconds(30), "terminal A after its view closed");
    awaitPidGone(pidB, Duration.ofSeconds(30), "terminal B after its view closed");

    stopDaemonAndAssertConverged();
    assertFalse(Files.exists(sessionSocket.getParent()), "private IPC directory must be released");
    assertFalse(isAlive(pidA), "terminal A pid must stay converged after daemon shutdown");
    assertFalse(isAlive(pidB), "terminal B pid must stay converged after daemon shutdown");
    completed = true;
  }

  /** 忽略 SIGTERM 的辅助进程仍须被强制终止，且必须等待真实 exit。 */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void forciblyTerminatesHelperThatIgnoresSigterm() throws Exception {
    assumeLinuxPosix();
    tempRoot = Files.createTempDirectory("kk-ide-browser-cleanup-");
    Files.setPosixFilePermissions(tempRoot, PosixFilePermissions.fromString("rwx------"));
    playwright =
        new ProcessBuilder(
                resolveNodeBinary().toString(),
                "-e",
                "process.on('SIGTERM', () => {});"
                    + "setInterval(() => {}, 1000); console.log('ready')")
            .start();
    assertEquals("ready", playwright.inputReader().readLine());
    stopProcessTree(playwright, "SIGTERM-ignoring helper");
    assertFalse(playwright.isAlive());
    assertTrue(helperProcesses.isEmpty());
    playwright = null;
    completed = true;
  }

  private void assumeLinuxPosix() throws IOException {
    Assumptions.assumeTrue(
        System.getProperty("os.name").toLowerCase().contains("linux"),
        "real IDE browser acceptance is verified on Linux only");
    Assumptions.assumeTrue(
        Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
            .supportsFileAttributeView("posix"),
        "POSIX owner-only credential model is required");
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set");
    }
    return value;
  }

  private static Path repositoryRoot() {
    // Surefire 在 web 模块目录下运行测试。
    return Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
  }

  private Path resolveNodeBinary() {
    String override = System.getenv("KK_STUDIO_NODE_BIN");
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    Path bundled = Path.of(requireEnv("KK_STUDIO_IDE_ACCEPTANCE_DIR")).resolve("lib/node");
    assertTrue(Files.isExecutable(bundled), "frozen code-server must ship lib/node: " + bundled);
    return bundled;
  }

  /**
   * 本用例不联网装依赖：{@code daemon/node_modules} 必须已就绪，否则明确失败并给出 setup 指引。随后每次都用生产 {@code npm run build}
   * 在用例独占目录重新构建（PATH 前置随包 Node24），不删除其它并行验证使用的 {@code dist}，也不复用陈旧产物。
   */
  private Path buildDaemon(Path daemonSource, Path node) throws Exception {
    if (!Files.isDirectory(daemonSource.resolve("node_modules"))) {
      fail("daemon/node_modules is missing; run `npm ci` in daemon/ before this acceptance test");
    }
    Path buildRoot = tempRoot.resolve("daemon-build");
    Files.createDirectories(buildRoot);
    copyTree(daemonSource.resolve("src"), buildRoot.resolve("src"));
    copyTree(daemonSource.resolve("ide-extension"), buildRoot.resolve("ide-extension"));
    for (String file : List.of("package.json", "tsconfig.json")) {
      Files.copy(daemonSource.resolve(file), buildRoot.resolve(file));
    }
    Files.createSymbolicLink(buildRoot.resolve("node_modules"), daemonSource.resolve("node_modules"));
    Map<String, String> environment = new HashMap<>();
    environment.put("PATH", node.toAbsolutePath().getParent() + File.pathSeparator + systemPath());
    environment.put("TMPDIR", processTmp.toString());
    runCommand(
        List.of("npm", "run", "build"),
        buildRoot,
        tempRoot.resolve("daemon-build.log"),
        600,
        environment);
    assertTrue(
        Files.isRegularFile(buildRoot.resolve("dist/main.js")),
        "daemon dist/main.js must exist after `npm run build`");
    return buildRoot;
  }

  /** 长数据目录仍须有真实可连接的 IPC，不能仅凭 workbench 可见判定完整启动。 */
  private Path assertCodeServerIpc(Path node) throws Exception {
    Set<Path> sockets = new LinkedHashSet<>();
    try (Stream<ProcessHandle> descendants = daemon.toHandle().descendants()) {
      descendants.forEach(
          child -> {
            String[] args = child.info().arguments().orElse(new String[0]);
            for (int index = 0; index + 1 < args.length; index++) {
              if ("--session-socket".equals(args[index])) {
                sockets.add(Path.of(args[index + 1]));
              }
            }
          });
    }
    assertEquals(1, sockets.size(), "one environment must own one private session IPC socket");
    Path socket = sockets.iterator().next();
    assertTrue(
        socket.toString().getBytes(StandardCharsets.UTF_8).length <= 107,
        "IPC path must fit the Linux kernel boundary");
    assertEquals(
        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(socket.getParent()));
    runCommand(
        List.of(
            node.toString(),
            "-e",
            "const http=require('node:http');"
                + "const req=http.request({socketPath:process.argv[1],"
                + "path:'/session?filePath=probe',agent:false},res=>{"
                + "res.resume();res.on('end',()=>{process.exitCode=res.statusCode===200?0:1})});"
                + "req.on('error',()=>{process.exitCode=1});req.end()",
            socket.toString()),
        tempRoot,
        tempRoot.resolve("ipc-probe.log"),
        10,
        Map.of());
    return socket;
  }

  private static String systemPath() {
    return System.getenv().getOrDefault("PATH", "");
  }

  /** 复制编译产物与扩展，code-server/Node 与依赖以符号链接落到独占暂存根。 */
  private Path stageDistribution(Path daemonSource, String codeServerDir) throws Exception {
    Path stage = tempRoot.resolve("stage");
    Files.createDirectories(stage.resolve("runtime"));
    copyTree(daemonSource.resolve("dist"), stage.resolve("dist"));
    copyTree(daemonSource.resolve("ide-extension"), stage.resolve("ide-extension"));
    Files.copy(
        daemonSource.resolve("package.json"),
        stage.resolve("package.json"),
        StandardCopyOption.REPLACE_EXISTING);
    Files.createSymbolicLink(stage.resolve("node_modules"), daemonSource.resolve("node_modules"));
    Files.createSymbolicLink(
        stage.resolve("runtime/code-server"), Path.of(codeServerDir).toAbsolutePath());
    return stage;
  }

  private void startDaemon(Path node, Path stage) throws IOException {
    Path privateDir = stage.resolve("data");
    Files.createDirectories(privateDir);
    Files.setPosixFilePermissions(privateDir, PosixFilePermissions.fromString("rwx------"));
    Path configPath = privateDir.resolve("daemon.json");
    Files.writeString(
        configPath, "{\"studioUrl\":\"http://localhost:" + port + "\"}", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"));
    Path tokenPath = privateDir.resolve("daemon.token");
    Files.writeString(tokenPath, REGISTRATION_TOKEN, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(tokenPath, PosixFilePermissions.fromString("rw-------"));

    daemonLog = tempRoot.resolve("daemon.log");
    ProcessBuilder builder =
        new ProcessBuilder(
            node.toString(),
            stage.resolve("dist/main.js").toString(),
            "--config",
            configPath.toString());
    // 让 Node os.tmpdir() 落在本用例私有目录：Daemon 的 native scope 只会在 process-tmp 下创建。
    builder.environment().put("TMPDIR", processTmp.toString());
    configureLoopbackNetwork(builder);
    builder.redirectErrorStream(true);
    builder.redirectOutput(daemonLog.toFile());
    daemon = builder.start();
  }

  /** 轮询真实注册表直到 Daemon 握手进入 READY；Daemon 提前退出即报错并附日志。 */
  private void awaitReady() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (System.nanoTime() < deadline) {
      if (!daemon.isAlive()) {
        fail("daemon exited before READY (code " + daemon.exitValue() + "):\n" + tail(daemonLog));
      }
      if (registry.hasReadyLease(ENVIRONMENT)) {
        return;
      }
      Thread.sleep(200);
    }
    fail("daemon did not reach READY in time:\n" + tail(daemonLog));
  }

  private EnvironmentIdeViewDTO openView(String viewId, Path directory) {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(viewId);
    request.setDirectory(directory.toString());
    return environmentIdeService.openView(ENVIRONMENT, request);
  }

  private String playwrightConfigJson(
      EnvironmentIdeViewDTO viewA, EnvironmentIdeViewDTO viewB, Path report, Path screenshots)
      throws JsonProcessingException {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("baseUrl", "http://localhost:" + port);
    config.put("environmentId", ENVIRONMENT.toString());
    config.put("reportPath", report.toAbsolutePath().toString());
    config.put("screenshotDir", screenshots.toAbsolutePath().toString());
    config.put("workspaceDirectory", workspaceDirectory.toAbsolutePath().toString());
    config.put(
        "views",
        List.of(
            viewConfig("A", VIEW_A, viewA.getEntryUrl()),
            viewConfig("B", VIEW_B, viewB.getEntryUrl())));
    // 用 Jackson 序列化含路径与一次性 entryUrl 的配置，避免手写 JSON 的转义错误。
    return OBJECT_MAPPER.writeValueAsString(config);
  }

  private static Map<String, Object> viewConfig(String name, String viewId, String entryUrl) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("name", name);
    view.put("viewId", viewId);
    view.put("entryUrl", entryUrl);
    return view;
  }

  private void runPlaywright(Path node, Path playwrightConfig, Path report) throws Exception {
    Path script =
        Path.of(
            "src",
            "test",
            "resources",
            "fun",
            "fengwk",
            "kkstudio",
            "web",
            "environment",
            "ide",
            "ide-browser-acceptance.mjs");
    String scriptPath =
        Path.of(System.getProperty("user.dir")).resolve(script).toAbsolutePath().toString();
    Path log = tempRoot.resolve("playwright.log");
    ProcessBuilder builder =
        new ProcessBuilder(node.toString(), scriptPath, "--config", playwrightConfig.toString());
    builder
        .environment()
        .put("KK_STUDIO_PLAYWRIGHT_MODULE_DIR", requireEnv("KK_STUDIO_PLAYWRIGHT_MODULE_DIR"));
    builder.environment().put("TMPDIR", processTmp.toString());
    configureLoopbackNetwork(builder);
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());
    Process process = builder.start();
    playwright = process;
    Throwable failure = null;
    try {
      if (!process.waitFor(5, TimeUnit.MINUTES)) {
        failure = new IllegalStateException("playwright acceptance did not finish:\n" + tail(log));
      } else if (process.exitValue() != 0) {
        failure =
            new IllegalStateException(
                "playwright acceptance failed (code " + process.exitValue() + "):\n" + tail(log));
      }
    } catch (Throwable error) {
      failure = error;
    } finally {
      try {
        stopProcessTree(process, "playwright");
        playwright = null;
      } catch (IOException cleanupFailure) {
        if (failure == null) {
          throw cleanupFailure;
        }
        failure.addSuppressed(cleanupFailure);
      }
    }
    if (failure != null) {
      rethrow(failure);
    }
    assertTrue(Files.isRegularFile(report), "playwright must write its report");
  }

  private static void rethrow(Throwable failure) throws Exception {
    if (failure instanceof Exception exception) {
      throw exception;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    throw new IllegalStateException(failure);
  }

  /** 真实整链只使用本用例 loopback 服务，不依赖执行者的环境或桌面代理配置。 */
  private static void configureLoopbackNetwork(ProcessBuilder builder) {
    builder.environment().put("http_proxy", "");
    builder.environment().put("HTTP_PROXY", "");
    builder.environment().put("https_proxy", "");
    builder.environment().put("HTTPS_PROXY", "");
    builder.environment().put("no_proxy", "");
    builder.environment().put("NO_PROXY", "");
  }

  private static void assertStep(JsonNode report, String step) {
    JsonNode node = report.path("steps").path(step);
    assertTrue(node.path("ok").asBoolean(false), "browser step '" + step + "' failed: " + node);
  }

  /**
   * 断言某个 workbench 步骤确实驱动于跨 Origin iframe 内：报告值须明确 {@code embedded=true}、父 harness 由真实 HTTP （非
   * route 构造）提供，且 IDE Origin 精确等于真实后端端口拼出的 Origin。退回顶层 {@code page.goto} 的驱动无法满足。
   */
  private void assertEmbeddedView(JsonNode report, String step) {
    JsonNode value = report.path("steps").path(step).path("value");
    assertTrue(
        value.path("embedded").asBoolean(false),
        step + " must prove the workbench was driven inside a real iframe: " + value);
    assertEquals(
        "http",
        value.path("parentTransport").asText(""),
        step + " parent harness must be served over real HTTP, not a route: " + value);
    String parentOrigin = value.path("parentOrigin").asText("");
    URI parent = URI.create(parentOrigin);
    assertEquals("http", parent.getScheme(), step + " parent scheme must be http: " + value);
    assertEquals(
        "studio.browser.localhost",
        parent.getHost(),
        step + " parent host must be the Studio host: " + value);
    assertTrue(
        parent.getPort() > 0, step + " parent must expose a real bound positive port: " + value);
    String ideOrigin = value.path("ideOrigin").asText("");
    assertEquals(
        "http://" + ENVIRONMENT + BROWSER_SUFFIX + ":" + port,
        ideOrigin,
        step + " IDE Origin must be the exact real backend Origin: " + value);
    assertNotEquals(
        parentOrigin, ideOrigin, step + " must be cross-Origin (parent != IDE): " + value);
  }

  private long readTerminalPid(String name, String expectedViewId) throws IOException {
    Path marker = workspaceDirectory.resolve("terminal-" + name + ".pid");
    assertTrue(Files.isRegularFile(marker), "terminal " + name + " must write its pid marker");
    String[] parts = Files.readString(marker).trim().split("\\s+");
    assertEquals(2, parts.length, "pid marker must carry viewId and pid: " + marker);
    assertEquals(expectedViewId, parts[0], "pid marker must carry the view identity");
    long pid = Long.parseLong(parts[1]);
    assertTrue(pid > 2, "terminal " + name + " must report a real OS pid");
    return pid;
  }

  private void awaitPidGone(long pid, Duration timeout, String label) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (!isAlive(pid)) {
        return;
      }
      Thread.sleep(200);
    }
    fail(label + ": pid " + pid + " did not converge");
  }

  private static boolean isAlive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private void stopDaemonAndAssertConverged() throws Exception {
    Process current = daemon;
    if (current == null) {
      fail("daemon was not started");
    }
    current.destroy();
    boolean exited = current.waitFor(60, TimeUnit.SECONDS);
    if (!exited) {
      // 只在本用例自己的进程树范围内升级；句柄保留到 exit 真正确认，让 @AfterEach 仍能补收敛。
      try {
        stopProcessTree(current, "daemon");
      } catch (IOException escalation) {
        log.error("daemon tree escalation failed: {}", escalation.getMessage());
      }
      exited = current.waitFor(10, TimeUnit.SECONDS);
    }
    if (!exited) {
      fail("daemon did not stop; native scope did not converge:\n" + tail(daemonLog));
    }
    daemon = null;
    assertEquals(
        0,
        current.exitValue(),
        "daemon must exit cleanly after proving every scope converged:\n" + tail(daemonLog));
    assertFalse(
        tail(daemonLog).contains("Invalid daemon configuration"), "daemon config must be valid");
    awaitNoNewScopes(Duration.ofSeconds(60));
  }

  /** 所有 scope 结束必须 await，而不是瞬时列表快照。 */
  private void awaitNoNewScopes(Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    Set<String> leftovers = new LinkedHashSet<>();
    while (System.nanoTime() < deadline) {
      leftovers = new LinkedHashSet<>(scopeDirectories());
      leftovers.removeAll(scopesBefore);
      if (leftovers.isEmpty()) {
        return;
      }
      Thread.sleep(500);
    }
    fail("native process scopes must converge after shutdown, leftovers=" + leftovers);
  }

  /** 只扫描本用例私有 {@code process-tmp} 里自己创建的 scope 目录；绝不全局扫 /tmp，避免把其它并发 agent 的 scope 误当作本用例泄漏。 */
  private Set<String> scopeDirectories() throws IOException {
    if (processTmp == null || !Files.isDirectory(processTmp)) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>();
    try (Stream<Path> stream = Files.list(processTmp)) {
      stream
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith(SCOPE_DIR_PREFIX))
          .forEach(names::add);
    }
    return names;
  }

  /** 销毁前保留自己启动的进程树句柄；失败后仍能在 AfterEach 收敛，不按随机 PID 杀进程。 */
  private void stopProcessTree(Process process, String label) throws IOException {
    if (process == null) {
      return;
    }
    helperProcesses.add(process.toHandle());
    helperProcesses.addAll(process.toHandle().descendants().toList());
    process.destroy();
    awaitExit(process.toHandle(), Duration.ofSeconds(5));
    stopHandles(helperProcesses, label);
    helperProcesses.clear();
  }

  private static void stopHandles(Set<ProcessHandle> owned, String label) throws IOException {
    List<ProcessHandle> alive = new ArrayList<>();
    for (ProcessHandle handle : owned) {
      if (handle.isAlive()) {
        handle.destroy();
        alive.add(handle);
      }
    }
    List<ProcessHandle> stubborn = new ArrayList<>();
    for (ProcessHandle handle : alive) {
      if (!awaitExit(handle, Duration.ofSeconds(5))) {
        handle.destroyForcibly();
        stubborn.add(handle);
      }
    }
    List<ProcessHandle> remaining = new ArrayList<>();
    for (ProcessHandle handle : stubborn) {
      if (!awaitExit(handle, Duration.ofSeconds(5))) {
        remaining.add(handle);
      }
    }
    if (!remaining.isEmpty()) {
      String pids =
          remaining.stream()
              .map(handle -> Long.toString(handle.pid()))
              .collect(Collectors.joining(","));
      throw new IOException(label + " did not terminate: " + pids);
    }
  }

  private boolean terminateRetainedHelpers() {
    try {
      stopHandles(helperProcesses, "acceptance helpers");
      helperProcesses.clear();
      return true;
    } catch (IOException error) {
      log.error("acceptance helpers did not terminate: {}", error.getMessage());
      return false;
    }
  }

  private static boolean awaitExit(ProcessHandle handle, Duration timeout) {
    try {
      handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException error) {
      return !handle.isAlive();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return !handle.isAlive();
    } catch (Exception error) {
      return !handle.isAlive();
    }
  }

  /** 只保留诊断产物；绝不拷贝配置、token、stage 或私有数据目录。 */
  private void retainArtifacts() {
    if (tempRoot == null) {
      return;
    }
    try {
      Path dir =
          repositoryRoot()
              .resolve("reports")
              .resolve("e2e")
              .resolve("ide-browser")
              .resolve(STAMP.format(LocalDateTime.now()));
      Files.createDirectories(dir);
      copyIfExists(tempRoot.resolve("daemon.log"), dir.resolve("daemon.log"));
      copyIfExists(tempRoot.resolve("playwright.log"), dir.resolve("playwright.log"));
      copyIfExists(tempRoot.resolve("report.json"), dir.resolve("report.json"));
      Path screenshots = tempRoot.resolve("screenshots");
      if (Files.isDirectory(screenshots)) {
        copyTree(screenshots, dir.resolve("screenshots"));
      }
      log.info("retained IDE browser acceptance artifacts at {}", dir.toAbsolutePath());
    } catch (Exception error) {
      log.warn("could not retain acceptance artifacts: {}", error.getMessage());
    }
  }

  private static void copyIfExists(Path source, Path target) throws IOException {
    if (Files.isRegularFile(source)) {
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** 停止本 Daemon；句柄保留到 exit 确认，失败返回 false 让 @AfterEach 显式 fail。 */
  private boolean terminateDaemon() {
    Process current = daemon;
    if (current == null) {
      return true;
    }
    try {
      stopProcessTree(current, "daemon");
      daemon = null;
      return true;
    } catch (IOException error) {
      log.error("daemon did not terminate: {}", error.getMessage());
      return false;
    }
  }

  /** 停止本次 Playwright/Chromium 树；失败返回 false 让 @AfterEach 显式 fail。 */
  private boolean terminatePlaywright() {
    Process current = playwright;
    if (current == null) {
      return true;
    }
    try {
      stopProcessTree(current, "playwright");
      playwright = null;
      return true;
    } catch (IOException error) {
      log.error("playwright did not terminate: {}", error.getMessage());
      return false;
    }
  }

  private static String tail(Path log) {
    try {
      List<String> lines = Files.readAllLines(log);
      int from = Math.max(0, lines.size() - 40);
      return String.join("\n", lines.subList(from, lines.size()));
    } catch (IOException error) {
      return "(log unavailable: " + error.getMessage() + ")";
    }
  }

  private void runCommand(
      List<String> command,
      Path cwd,
      Path log,
      long timeoutSeconds,
      Map<String, String> environment)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
    builder.environment().putAll(environment);
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());
    Process process = builder.start();
    Throwable failure = null;
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        failure = new IllegalStateException("command timed out: " + command + "\n" + tail(log));
      } else if (process.exitValue() != 0) {
        failure =
            new IllegalStateException(
                "command failed (" + process.exitValue() + "): " + command + "\n" + tail(log));
      }
    } catch (Throwable error) {
      failure = error;
    } finally {
      try {
        stopProcessTree(process, "command " + command.get(0));
      } catch (IOException cleanupFailure) {
        if (failure == null) {
          throw cleanupFailure;
        }
        failure.addSuppressed(cleanupFailure);
      }
    }
    if (failure != null) {
      rethrow(failure);
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(
                file,
                target.resolve(source.relativize(file).toString()),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(root)) {
      stream.forEach(paths::add);
    }
    for (int index = paths.size() - 1; index >= 0; index--) {
      Files.deleteIfExists(paths.get(index));
    }
  }
}
