package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 三个 LSP capability 的端到端契约：参数校验之后走真实客户端与真实假服务器进程。
 *
 * <p>这里覆盖 bridge 时代被替换掉的行为：workdir 不再交给外部进程，路径与源码正文不做任何相对化改写，超时按调用方有效超时收敛，取消只结束本次请求而不关闭 Daemon
 * 共享的客户端。
 */
class LspCapabilitiesTest {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /** LSP 客户端的阻塞 stdio 由 Daemon 拥有的平台线程承担；测试里由本类自己持有。 */
  private final ExecutorService lspDispatch = Executors.newCachedThreadPool();

  private final ScheduledExecutorService lspScheduler =
      Executors.newSingleThreadScheduledExecutor();
  private final List<LspService> services = new ArrayList<>();

  @AfterEach
  void tearDown() {
    services.forEach(LspService::close);
    lspScheduler.shutdownNow();
    lspDispatch.shutdownNow();
    executor.shutdownNow();
  }

  /** 意图：definition 结果按"路径:行:列"返回，服务器进程目录来自自动发现的项目根而不是调用方 workdir。 */
  @Test
  void gotoDefinitionReturnsAbsoluteLocationsFromDiscoveredRoot() throws Exception {
    Path repo = Files.createDirectories(root.resolve("repo"));
    Files.createDirectories(repo.resolve(".git"));
    Files.writeString(repo.resolve("pom.xml"), "<project/>");
    Path module = Files.createDirectories(repo.resolve("module"));
    Path file = Files.writeString(module.resolve("App.java"), "class App {\n  void run() {}\n}\n");
    Path transcript = FakeLspServers.transcript(root);
    CodingToolsConfig config = TestCodingConfig.withFakeLsp(root, "normal", transcript);
    LspService service = service(config);

    EnvironmentCapabilityResult result =
        invoke(
            new LspGotoDefinitionCapability(config, service, executor),
            "{\"path\":\"module/App.java\",\"line\":1,\"character\":0,\"workdir\":"
                + json(repo.toString())
                + "}");

    assertFalse(result.error(), text(result));
    assertEquals(file + ":10:5", text(result));
    // 服务器看到的文件 URI 是真实绝对路径：不做相对化改写。
    assertEquals(
        file.toRealPath().toUri().toString(),
        FakeLspServers.received(transcript, "textDocument/didOpen")
            .getFirst()
            .path("params")
            .path("textDocument")
            .path("uri")
            .asText());
  }

  /** 意图：workspace symbols 与 java decompile 走同一份共享客户端，源码正文原样透传。 */
  @Test
  void workspaceSymbolsAndDecompileShareOneClient() throws Exception {
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path classFile = Files.createDirectories(root.resolve("build")).resolve("App.class");
    Files.write(classFile, new byte[] {0x1});
    Path transcript = FakeLspServers.transcript(root);
    CodingToolsConfig config = TestCodingConfig.withFakeLsp(root, "normal", transcript);
    LspService service = service(config);

    EnvironmentCapabilityResult symbols =
        invoke(
            new LspWorkspaceSymbolsCapability(config, service, executor),
            "{\"path\":\"App.java\",\"query\":\"A\",\"limit\":2,\"workdir\":"
                + json(root.toString())
                + "}");
    EnvironmentCapabilityResult decompiled =
        invoke(
            new LspJavaDecompileCapability(config, service, executor),
            "{\"path\":\"App.java\",\"target\":\"build/App.class\",\"workdir\":"
                + json(root.toString())
                + "}");

    assertFalse(symbols.error(), text(symbols));
    assertEquals(
        "Alpha (Class) - file:///tmp/alpha.java\nBeta (Function) - file:///tmp/beta.java",
        text(symbols));
    assertFalse(decompiled.error(), text(decompiled));
    assertEquals(FakeLspServer.DECOMPILED_SOURCE, text(decompiled));
    assertEquals(1, service.activeClientCount(), "三个能力共享同一份客户端");
  }

  /** 意图：超过调用方有效超时的请求在有效超时附近失败返回，并保留共享客户端。 */
  @Test
  void timeoutFailsAtTheCallerDeadlineWithoutClosingTheSharedClient() throws Exception {
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path transcript = FakeLspServers.transcript(root);
    CodingToolsConfig config = TestCodingConfig.withFakeLsp(root, "slow-definition", transcript);
    LspService slow = service(config);

    long started = System.nanoTime();
    EnvironmentCapabilityResult result =
        invoke(
            new LspGotoDefinitionCapability(config, slow, executor),
            "{\"path\":\"App.java\",\"line\":1,\"workdir\":" + json(root.toString()) + "}",
            Duration.ofMillis(500));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    assertTrue(result.error(), text(result));
    assertTrue(text(result).contains("timed out"), text(result));
    assertTrue(elapsedMillis < 15_000, "必须在调用方有效超时附近返回，实际 " + elapsedMillis + "ms");
    assertEquals(1, slow.activeClientCount(), "超时不得丢弃/关闭共享客户端");
  }

  /** 意图：取消只结束本次请求：客户端保持可用，后续同根调用继续复用。 */
  @Test
  void cancellationKeepsTheSharedClientAlive() throws Exception {
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path transcript = FakeLspServers.transcript(root);
    CodingToolsConfig config = TestCodingConfig.withFakeLsp(root, "slow-definition", transcript);
    LspService slow = service(config);
    RecordingListener listener =
        invokeAsync(
            new LspGotoDefinitionCapability(config, slow, executor),
            "{\"path\":\"App.java\",\"line\":1,\"workdir\":" + json(root.toString()) + "}",
            Duration.ofSeconds(30));
    FakeLspServers.await(transcript, event -> "textDocument/definition".equals(methodOf(event)));

    listener.handle.cancel();
    assertTrue(listener.await());
    assertTrue(text(listener.result).contains("Operation cancelled"), text(listener.result));
    assertEquals(1, slow.activeClientCount(), "取消不得关闭共享客户端");
  }

  /** 意图：read header 的 LSP 状态完全由配置驱动：未配置为 unsupported，配置命中给出服务器语言标签。 */
  @Test
  void readReportsConfiguredLspStatus() throws Exception {
    Files.writeString(root.resolve("App.txt"), "x\n");
    CodingToolsConfig withoutLsp = TestCodingConfig.withoutLsp(root);
    CodingToolsConfig withLsp = TestCodingConfig.withLsp(root);

    EnvironmentCapabilityResult disabled =
        invoke(
            new ReadCapability(withoutLsp, executor),
            "{\"path\":\"App.txt\",\"workdir\":" + json(root.toString()) + "}");
    EnvironmentCapabilityResult enabled =
        invoke(
            new ReadCapability(withLsp, executor),
            "{\"path\":\"App.txt\",\"workdir\":" + json(root.toString()) + "}");

    assertFalse(text(disabled).contains("lsp:"), text(disabled));
    assertTrue(
        text(enabled).contains("lsp: supported (" + TestCodingConfig.LSP_SERVER_ID + ")"),
        text(enabled));
  }

  /**
   * 意图：参数校验分两层且都在启动服务器之前——缺失必填项在 inputSchema 层直接拒绝，数值越界在 capability 内转成可读错误结果；
   * 只有全部通过后才会真正启动服务器并使用默认 character=0。
   */
  @Test
  void argumentValidationRunsBeforeAnyServerProcess() throws Exception {
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path transcript = FakeLspServers.transcript(root);
    CodingToolsConfig config = TestCodingConfig.withFakeLsp(root, "normal", transcript);
    LspService service = service(config);
    EnvironmentCapability gotoDefinition =
        new LspGotoDefinitionCapability(config, service, executor);
    EnvironmentCapability workspaceSymbols =
        new LspWorkspaceSymbolsCapability(config, service, executor);
    EnvironmentCapability decompile = new LspJavaDecompileCapability(config, service, executor);
    String workdir = json(root.toString());

    IllegalArgumentException missingLine =
        assertThrows(
            IllegalArgumentException.class,
            () -> execute(gotoDefinition, "{\"path\":\"App.java\",\"workdir\":" + workdir + "}"));
    assertTrue(missingLine.getMessage().contains("$.line"), missingLine.getMessage());

    EnvironmentCapabilityResult zeroLine =
        invoke(gotoDefinition, "{\"path\":\"App.java\",\"line\":0,\"workdir\":" + workdir + "}");
    assertTrue(zeroLine.error(), text(zeroLine));
    assertTrue(text(zeroLine).contains("line"), text(zeroLine));

    EnvironmentCapabilityResult negativeCharacter =
        invoke(
            gotoDefinition,
            "{\"path\":\"App.java\",\"line\":1,\"character\":-1,\"workdir\":" + workdir + "}");
    assertTrue(negativeCharacter.error(), text(negativeCharacter));
    assertTrue(text(negativeCharacter).contains("character"), text(negativeCharacter));

    EnvironmentCapabilityResult negativeLimit =
        invoke(
            workspaceSymbols,
            "{\"path\":\"App.java\",\"query\":\"App\",\"limit\":-1,\"workdir\":" + workdir + "}");
    assertTrue(negativeLimit.error(), text(negativeLimit));

    EnvironmentCapabilityResult blankTarget =
        invoke(decompile, "{\"path\":\"App.java\",\"target\":\"  \",\"workdir\":" + workdir + "}");
    assertTrue(blankTarget.error(), text(blankTarget));
    assertTrue(text(blankTarget).contains("target"), text(blankTarget));

    assertEquals(0, service.activeClientCount(), "参数校验失败不得启动服务器");
    EnvironmentCapabilityResult defaultCharacter =
        invoke(
            gotoDefinition,
            "{\"path\":\"" + file.getFileName() + "\",\"line\":1,\"workdir\":" + workdir + "}");
    assertFalse(defaultCharacter.error(), text(defaultCharacter));
    assertEquals(
        0,
        FakeLspServers.received(transcript, "textDocument/definition")
            .getFirst()
            .path("params")
            .path("position")
            .path("character")
            .asInt());
  }

  /** 意图：绝对 path 不需要 workdir——三个能力都能在没有调用方目录的情况下完成真实查询与执行。 */
  @Test
  void absolutePathsNeedNoWorkdir() throws Exception {
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path build = Files.createDirectories(root.resolve("build"));
    Path classFile = build.resolve("App.class");
    Files.write(classFile, new byte[] {0x1});
    Path transcript = FakeLspServers.transcript(root);
    CodingToolsConfig config = TestCodingConfig.withFakeLsp(root, "normal", transcript);
    LspService service = service(config);
    String absolute = json(file.toString());

    EnvironmentCapabilityResult definition =
        invoke(
            new LspGotoDefinitionCapability(config, service, executor),
            "{\"path\":" + absolute + ",\"line\":1}");
    EnvironmentCapabilityResult symbols =
        invoke(
            new LspWorkspaceSymbolsCapability(config, service, executor),
            "{\"path\":" + absolute + ",\"query\":\"A\"}");
    EnvironmentCapabilityResult decompiled =
        invoke(
            new LspJavaDecompileCapability(config, service, executor),
            "{\"path\":" + absolute + ",\"target\":" + json(classFile.toString()) + "}");

    assertFalse(definition.error(), text(definition));
    assertFalse(symbols.error(), text(symbols));
    assertFalse(decompiled.error(), text(decompiled));
    assertEquals(FakeLspServer.DECOMPILED_SOURCE, text(decompiled));
    assertEquals(
        classFile.toUri().toString(),
        FakeLspServers.received(transcript, "workspace/executeCommand")
            .getFirst()
            .path("params")
            .path("arguments")
            .get(0)
            .asText());
    // 文档 URI 是真实绝对路径；服务器进程目录来自自动发现的项目根，调用方不必提供任何目录。
    assertEquals(
        file.toRealPath().toUri().toString(),
        FakeLspServers.received(transcript, "textDocument/didOpen")
            .getFirst()
            .path("params")
            .path("textDocument")
            .path("uri")
            .asText());
    assertEquals(1, service.activeClientCount());
  }

  /** 意图：相对 path 与相对 class target 必须由本次调用显式给出绝对 workdir，缺失时直接拒绝且不启动服务器。 */
  @Test
  void relativePathsRequireAnExplicitWorkdir() throws Exception {
    Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path transcript = FakeLspServers.transcript(root);
    CodingToolsConfig config = TestCodingConfig.withFakeLsp(root, "normal", transcript);
    LspService service = service(config);

    EnvironmentCapabilityResult definition =
        invoke(
            new LspGotoDefinitionCapability(config, service, executor),
            "{\"path\":\"App.java\",\"line\":1}");
    assertTrue(definition.error(), text(definition));
    assertTrue(
        text(definition).contains("workdir is required when path is a relative path"),
        text(definition));

    EnvironmentCapabilityResult symbols =
        invoke(
            new LspWorkspaceSymbolsCapability(config, service, executor),
            "{\"path\":\"App.java\",\"query\":\"A\"}");
    assertTrue(symbols.error(), text(symbols));
    assertTrue(
        text(symbols).contains("workdir is required when path is a relative path"), text(symbols));

    EnvironmentCapabilityResult decompiled =
        invoke(
            new LspJavaDecompileCapability(config, service, executor),
            "{\"path\":"
                + json(root.resolve("App.java").toString())
                + ",\"target\":\"build/App.class\"}");
    assertTrue(decompiled.error(), text(decompiled));
    assertTrue(
        text(decompiled).contains("workdir is required when target is a relative class path"),
        text(decompiled));

    assertTrue(FakeLspServers.events(transcript).isEmpty(), "缺少 workdir 不得启动服务器");
    assertEquals(0, service.activeClientCount(), "缺少 workdir 不得占用客户端");
  }

  private LspService service(CodingToolsConfig config) {
    LspService service =
        LspService.create(
            config.lsp(),
            lspDispatch,
            lspScheduler,
            Duration.ofMinutes(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(30));
    services.add(service);
    return service;
  }

  private static String methodOf(JsonNode event) {
    return event.path("message").path("method").asText("");
  }

  /** 调用方有效超时是 LSP 请求的真实 deadline，因此默认给足时间，只有超时用例才收紧。 */
  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    return invoke(capability, arguments, REQUEST_TIMEOUT);
  }

  private EnvironmentCapabilityResult invoke(
      EnvironmentCapability capability, String arguments, Duration timeout) throws Exception {
    RecordingListener listener = invokeAsync(capability, arguments, timeout);
    assertTrue(listener.await());
    return listener.result;
  }

  private RecordingListener invokeAsync(
      EnvironmentCapability capability, String arguments, Duration timeout) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(), new EnvironmentCapabilityCall("lsp", arguments), timeout),
            listener);
    return listener;
  }

  private static void execute(EnvironmentCapability capability, String arguments) {
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("lsp", arguments),
            REQUEST_TIMEOUT),
        new RecordingListener());
  }

  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private static String text(EnvironmentCapabilityResult result) {
    return text(result.contents());
  }

  private static String text(List<ResultContent> contents) {
    return contents.stream().map(LspCapabilitiesTest::text).reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent value ? value.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;
    private volatile EnvironmentCapabilityExecutionHandle handle;
    private volatile int completions;

    @Override
    public synchronized void onPartial(EnvironmentCapabilityResult partial) {
      // 导航类能力没有增量输出。
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completions++;
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
