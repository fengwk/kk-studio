package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.daemon.DaemonCapabilityRegistry;
import fun.fengwk.kkstudio.harness.daemon.DaemonConfig;
import fun.fengwk.kkstudio.harness.daemon.skill.SkillPackageInstaller;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Daemon 装配的 LSP 切片：内联 lsp 声明的扩展名必须真的决定能力可用性，并且装配好的注册表能端到端跑通。
 *
 * <p>这里是"配置 → 发现 → 客户端 → capability"的完整链路测试：配置来自唯一 Daemon JSON 文件，服务器进程来自假服务器，断言读自真实协议记录。
 */
class LspDaemonWiringTest {

  /** 调用方有效超时就是 LSP 请求的 deadline。 */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /** LSP 客户端的阻塞 stdio 由 Daemon 拥有的平台线程承担；测试里由本类自己持有。 */
  private final ExecutorService lspDispatch = Executors.newCachedThreadPool();

  /** 计时器与能力调度共享一个线程池：LSP 空闲回收只是一次计时任务。 */
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  private final List<LspService> services = new ArrayList<>();

  @AfterEach
  void tearDown() {
    services.forEach(LspService::close);
    lspDispatch.shutdownNow();
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  /** 意图：配置声明 {@code .java} 后 lsp capability 端到端可用；未声明的扩展名给出可操作错误。 */
  @Test
  void inlineConfiguredExtensionsDriveTheRegisteredCapabilities() throws Exception {
    Path repo = Files.createDirectories(root.resolve("repo"));
    Files.createDirectories(repo.resolve(".git"));
    Files.writeString(repo.resolve("pom.xml"), "<project/>");
    Path javaFile = Files.writeString(repo.resolve("App.java"), "class App {}\n");
    Path pythonFile = Files.writeString(repo.resolve("script.py"), "print(1)\n");
    Path transcript = FakeLspServers.transcript(root);
    Path configFile = writeLspConfig(transcript);

    LspDiscovery discovery = discovery(configFile);
    CodingToolsConfig config =
        CodingToolsConfig.fromRuntime(
            Files.createDirectories(root.resolve("data/resources")), "bash", discovery);
    LspService service =
        LspService.create(
            discovery,
            lspDispatch,
            scheduler,
            Duration.ofMinutes(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(30));
    services.add(service);

    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    CodingCapabilities.registerAll(
        registry,
        config,
        service,
        new SkillPackageInstaller(
            root.resolve("skills"),
            root.resolve("cache"),
            root.resolve("staging"),
            root.resolve("backup"),
            executor),
        executor,
        scheduler);
    EnvironmentCapability gotoDefinition =
        registry.find(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION).orElseThrow();

    EnvironmentCapabilityResult result =
        invoke(
            gotoDefinition,
            "{\"path\":\"App.java\",\"line\":1,\"workdir\":" + json(repo.toString()) + "}");

    assertFalse(result.error(), text(result));
    assertEquals(javaFile + ":10:5", text(result));
    // 服务器进程目录就是自动发现的项目根：调用方 workdir 只用于解析相对路径。
    assertEquals(
        repo.toRealPath().toString(),
        FakeLspServers.await(transcript, event -> event.path("event").asText().equals("start"))
            .path("cwd")
            .asText(),
        "LSP 进程必须运行在发现到的项目根");

    // .py 没有声明的服务器：能力给出可操作错误，而不是尝试启动别的程序。
    EnvironmentCapabilityResult unconfigured =
        invoke(
            gotoDefinition,
            "{\"path\":\"script.py\",\"line\":1,\"workdir\":" + json(repo.toString()) + "}");
    assertTrue(unconfigured.error(), text(unconfigured));
    assertTrue(text(unconfigured).contains("No LSP server configured"), text(unconfigured));
    assertTrue(Files.exists(pythonFile));
  }

  /** 意图：配置声明的扩展名大小写不敏感，且 read header 会报告"类型受支持但未安装"。 */
  @Test
  void configuredExtensionsAreCaseInsensitiveAndReportNotInstalled() throws Exception {
    Path config = root.resolve("daemon.json");
    Files.writeString(
        config,
        "{\"studioUrl\":\"http://localhost\",\"lsp\":{\"servers\":{\"kt-ls\":{\"command\":[\"/nope/missing-language-server\"],"
            + "\"extensions\":[\".KT\"]}}}}",
        StandardCharsets.UTF_8);
    Path file = Files.writeString(root.resolve("App.KT"), "val app = 1\n");

    LspDiscovery discovery = discovery(config);
    LspSupport support = discovery.support(file);

    assertTrue(support.supported(), "扩展名匹配必须大小写不敏感");
    assertFalse(support.available());
    assertEquals("kt-ls", support.language().orElseThrow());
  }

  private Path writeLspConfig(Path transcript) throws Exception {
    Path config = root.resolve("daemon.json");
    List<String> command = new ArrayList<>();
    command.add(FakeLspServers.javaExecutable());
    command.add("-cp");
    command.add(FakeLspServers.childClasspath());
    command.add(FakeLspServer.class.getName());
    command.add("normal");
    command.add(transcript.toString());
    StringBuilder jsonCommand = new StringBuilder();
    for (String element : command) {
      if (jsonCommand.length() > 0) {
        jsonCommand.append(',');
      }
      jsonCommand.append(quote(element));
    }
    Files.writeString(
        config,
        "{\"studioUrl\":\"http://localhost\",\"lsp\":{\"servers\":{\"fake\":{\"command\":["
            + jsonCommand
            + "],\"extensions\":[\".java\"],\"rootMarkers\":[\"pom.xml\"]}}}}",
        StandardCharsets.UTF_8);
    return config;
  }

  private LspDiscovery discovery(Path config) throws Exception {
    Path token = Files.writeString(config.resolveSibling("daemon.token"), "test-token");
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(
          token, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
    return DaemonConfig.fromArgs(new String[] {"--config", config.toString()}).lsp();
  }

  private static String quote(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(),
                new EnvironmentCapabilityCall("wiring", arguments),
                REQUEST_TIMEOUT),
            listener);
    assertTrue(listener.await());
    return listener.result;
  }

  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private static String text(EnvironmentCapabilityResult result) {
    return text(result.contents());
  }

  private static String text(List<ResultContent> contents) {
    return contents.stream()
        .map(content -> content instanceof TextResultContent value ? value.text() : "")
        .reduce("", String::concat);
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;
    private volatile EnvironmentCapabilityExecutionHandle handle;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      // 导航类能力没有增量输出。
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      done.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }

    private boolean await() throws InterruptedException {
      return done.await(30, TimeUnit.SECONDS);
    }
  }
}
