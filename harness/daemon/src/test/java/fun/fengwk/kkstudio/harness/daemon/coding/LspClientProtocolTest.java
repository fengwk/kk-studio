package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link LspClient} 的协议与失败路径：结果形态、错误应答、位置边界、启动失败、崩溃诊断、以及服务端请求的本地应答。
 *
 * <p>这些用例把"正常路径之外的确定性行为"钉住：空结果、错误应答、位置编码边界、进程提前退出、诊断尾部有界。
 */
class LspClientProtocolTest {

  private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ExecutorService dispatch = Executors.newCachedThreadPool();
  private final List<LspClient> clients = new ArrayList<>();
  private Path transcript;

  @AfterEach
  void tearDown() {
    clients.forEach(client -> client.stop(Duration.ofMillis(200)));
    dispatch.shutdownNow();
  }

  /** 意图：LocationLink 形态的定义结果按目标选择区间格式化；空结果给出"没有结果"而不是空串。 */
  @Test
  void formatsLocationLinksAndEmptyResults() throws Exception {
    LspClient linkClient = start("link-definition");
    Path file = write("App.java", "class App {}\n");
    assertEquals(List.of(file + ":10:5"), linkClient.definition(file, 1, 0, REQUEST_TIMEOUT));

    LspClient nullClient = start("null-definition");
    assertEquals(List.of("No results found"), nullClient.definition(file, 1, 0, REQUEST_TIMEOUT));
  }

  /** 意图：服务器回错误应答时请求以上下文信息失败，而不是被当成空结果。 */
  @Test
  void serverErrorFailsTheRequest() throws Exception {
    LspClient client = start("error-definition");
    Path file = write("App.java", "class App {}\n");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> client.definition(file, 1, 0, REQUEST_TIMEOUT));
    assertTrue(error.getMessage().contains("textDocument/definition"), error.getMessage());
    assertTrue(client.isAlive(), "错误应答不是连接故障");
  }

  /** 意图：符号结果的两种形态（SymbolInformation 与 WorkspaceSymbol）都能格式化，空/缺失结果给出固定文案。 */
  @Test
  void formatsWorkspaceSymbolShapes() throws Exception {
    assertEquals(
        List.of("Delta (Class) - file:///tmp/delta.java"),
        start("workspace-symbols").workspaceSymbols("D", 10, REQUEST_TIMEOUT));
    assertEquals(
        List.of("No symbols found"),
        start("null-symbols").workspaceSymbols("D", 10, REQUEST_TIMEOUT));
    assertEquals(
        List.of("No symbols found"),
        start("empty-symbols").workspaceSymbols("D", 10, REQUEST_TIMEOUT));
  }

  /** 意图：服务器未声明 workspace symbol 能力时工具给出可操作错误且不发请求。 */
  @Test
  void unsupportedWorkspaceSymbolsAreReportedWithoutRequest() throws Exception {
    LspClient client = start("no-symbols");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> client.workspaceSymbols("A", 10, REQUEST_TIMEOUT));
    assertTrue(
        error.getMessage().contains("does not advertise workspace symbol"), error.getMessage());
    assertTrue(FakeLspServers.received(transcript, "workspace/symbol").isEmpty());
  }

  /** 意图：CRLF 行、末行无换行、行首行尾与越界列都按码点语义换算；utf-32 位置编码也受支持。 */
  @Test
  void positionBoundariesCoverCrlfLastLineAndUtf32() throws Exception {
    LspClient client = start("normal");
    Path crlf = write("Crlf.java", "class A {}\r\nclass B {}\n");
    client.definition(crlf, 1, 9, REQUEST_TIMEOUT);
    JsonNode first =
        FakeLspServers.received(transcript, "textDocument/definition").getFirst().path("params");
    assertEquals(0, first.path("position").path("line").asInt());
    assertEquals(9, first.path("position").path("character").asInt());

    // 末行没有换行符：整行是合法位置，恰好等于码点数的列也合法。
    Path noNewline = write("NoNewline.java", "class App {}\nclass Last {}");
    client.definition(noNewline, 2, 13, REQUEST_TIMEOUT);
    IllegalArgumentException beyondTail =
        assertThrows(
            IllegalArgumentException.class,
            () -> client.definition(noNewline, 3, 0, REQUEST_TIMEOUT));
    assertTrue(beyondTail.getMessage().contains("beyond the end"), beyondTail.getMessage());

    LspClient utf32 = start("utf32");
    Path emoji = write("Emoji.java", "\"😀x\";\n");
    utf32.definition(emoji, 1, 2, REQUEST_TIMEOUT);
    JsonNode utf32Position =
        FakeLspServers.received(transcript, "textDocument/definition")
            .getLast()
            .path("params")
            .path("position");
    assertEquals(2, utf32Position.path("character").asInt(), "utf-32 下码点索引与列一致");
  }

  /** 意图：本地 class 目标的三种写法与错误写法都被明确处理，空源码不被当作成功。 */
  @Test
  void localClassTargetVariantsAndEmptySource() throws Exception {
    LspClient client = start("normal");
    Files.createDirectories(root.resolve("build"));
    Path classFile = Files.write(root.resolve("build/App.class"), new byte[] {1});

    assertEquals(
        FakeLspServer.DECOMPILED_SOURCE,
        client.javaDecompile(root, classFile.toString(), REQUEST_TIMEOUT));
    assertEquals(
        FakeLspServer.DECOMPILED_SOURCE,
        client.javaDecompile(root, classFile.toUri().toString(), REQUEST_TIMEOUT));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.javaDecompile(root, "missing/App.class", REQUEST_TIMEOUT));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.javaDecompile(root, "file://", REQUEST_TIMEOUT));

    LspClient empty = start("empty-decompile");
    IllegalStateException noSource =
        assertThrows(
            IllegalStateException.class,
            () -> empty.javaDecompile(root, "build/App.class", REQUEST_TIMEOUT));
    assertTrue(
        noSource.getMessage().contains("Could not load or decompile"), noSource.getMessage());
  }

  /** 意图：spawn 成功后 stderr 派发被拒时，已拉起的进程必须被终止，调用方拿到的是原始拒绝异常而不是进程泄漏。 */
  @Test
  void rejectedDispatchAfterSpawnTerminatesTheProcess() throws Exception {
    Path pidFile = root.resolve("held.pid");
    LspServerConfig config = holdingServer(pidFile);
    RejectingExecutor rejecting = new RejectingExecutor();

    Thread launch =
        new Thread(
            () -> {
              try {
                LspClient.launch(config, root, config.command().getFirst(), rejecting);
              } catch (RuntimeException error) {
                rejecting.failure = error;
              }
            },
            "lsp-reject-launch");
    launch.start();
    long pid = awaitPidFile(pidFile);
    launch.join(Duration.ofSeconds(20).toMillis());

    assertFalse(launch.isAlive(), "拒绝后的 launch 必须返回");
    assertTrue(
        rejecting.failure instanceof RejectedExecutionException, String.valueOf(rejecting.failure));
    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
  }

  /** 意图：stderr 任务已进入所有权窗口但尚未返回时，启动探测失败必须终止同一进程，而不是把活进程交还给调用方。 */
  @Test
  void probeFailureWhileDispatchIsHeldTerminatesTheProcess() throws Exception {
    Path transcript = transcript();
    LspServerConfig config = FakeLspServers.deadServer("held-dead", 9);
    HoldingExecutor holding = new HoldingExecutor();

    Thread launch =
        new Thread(
            () -> {
              try {
                LspClient.launch(config, root, config.command().getFirst(), holding);
              } catch (RuntimeException error) {
                holding.failure = error;
              }
            },
            "lsp-launch-hold");
    launch.start();
    assertTrue(holding.entered.await(20, TimeUnit.SECONDS), "stderr 任务必须进入所有权窗口");
    holding.release.set(true);
    launch.join(Duration.ofSeconds(20).toMillis());

    assertFalse(launch.isAlive(), "所有权窗口结束后 launch 必须返回");
    assertTrue(holding.failure != null, "提前退出的服务器必须让 launch 失败");
    assertTrue(
        holding.failure.getMessage().contains("exited before initialization"),
        holding.failure.getMessage());
  }

  /** 意图：服务器在初始化前退出时启动失败并给出退出码与诊断；不会留下半初始化实例。 */
  @Test
  void deadServerFailsFastAtLaunch() {
    assumeFalse(
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
        "shell 退出语义仅 POSIX");
    LspServerConfig config = FakeLspServers.deadServer("died", 7);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> LspClient.launch(config, root, config.command().getFirst(), dispatch));
    assertTrue(error.getMessage().contains("exited before initialization"), error.getMessage());
    assertTrue(error.getMessage().contains("exit code 7"), error.getMessage());
  }

  /** 意图：初始化不返回时握手在有效超时内失败，进程被收敛。 */
  @Test
  void initializeTimeoutTerminatesTheProcess() throws Exception {
    Path transcript = transcript();
    LspServerConfig config = FakeLspServers.javaServer("noinit", "no-init", root, transcript);
    LspClient client = LspClient.launch(config, root, config.command().getFirst(), dispatch);
    clients.add(client);

    Exception error =
        assertThrows(Exception.class, () -> client.initialize(Duration.ofMillis(500)));

    assertTrue(error.getMessage().contains("timed out"), error.getMessage());
    assertFalse(client.isAlive());
    FakeLspServers.awaitProcessGone(FakeLspServers.startedPid(transcript), Duration.ofSeconds(10));
  }

  /** 意图：服务器崩溃后的后续调用与关闭都稳定，错误信息带上真实的退出码与有界诊断。 */
  @Test
  void crashedServerReportsExitCodeAndBoundedDiagnostics() throws Exception {
    LspClient client = start("crash-noisy");
    Path file = write("App.java", "class App {}\n");
    assertThrows(IllegalStateException.class, () -> client.definition(file, 1, 0, REQUEST_TIMEOUT));
    assertFalse(client.isAlive());

    IllegalStateException afterCrash =
        assertThrows(
            IllegalStateException.class, () -> client.definition(file, 1, 0, REQUEST_TIMEOUT));
    assertTrue(afterCrash.getMessage().contains("exited"), afterCrash.getMessage());
    assertTrue(afterCrash.getMessage().contains("exit code 11"), afterCrash.getMessage());
    assertTrue(
        afterCrash.getMessage().length() < 2 * 1024,
        "stderr 诊断尾部必须有界：" + afterCrash.getMessage().length());

    // 重复关闭幂等。
    client.stop(Duration.ofMillis(200));
    client.stop(Duration.ofMillis(200));
    assertFalse(client.isAlive());
  }

  /** 意图：服务端发来的提示、遥测、诊断、编辑与进度类消息都在本地被稳定应答，不产生协议错误。 */
  @Test
  void answersClientFacingServerMessages() throws Exception {
    LspClient client = start("client-requests");

    // 两个服务端请求都必须被应答：showMessageRequest 与 applyEdit 都不会让连接失败。
    JsonNode showMessageRequest = awaitResponse(900);
    assertTrue(showMessageRequest.path("result").isNull(), showMessageRequest.toString());
    JsonNode applyEdit = awaitResponse(901);
    assertFalse(applyEdit.path("result").isMissingNode(), applyEdit.toString());
    assertFalse(applyEdit.path("result").path("applied").asBoolean(), "本客户端不应用编辑");

    // 通知类消息不产生应答，但连接仍然可用：后续请求照常往返。
    Path file = write("App.java", "class App {}\n");
    assertEquals(List.of(file + ":10:5"), client.definition(file, 1, 0, REQUEST_TIMEOUT));
    assertEquals(1, startedProcesses());
  }

  /** 意图：符号条目缺失 name/kind/location 时给出确定文案；定义缺失 uri/range 时也不抛异常。 */
  @Test
  void oddSymbolsAndLocationsAreFormattedDeterministically() throws Exception {
    assertEquals(
        List.of("unknown (unknown) - unknown"),
        start("odd-symbols").workspaceSymbols("x", 10, REQUEST_TIMEOUT));

    List<String> locations =
        start("weird-locations")
            .definition(write("App.java", "class App {}\n"), 1, 0, REQUEST_TIMEOUT);
    assertEquals(List.of("jdt://contents/pkg/Thing.class", "unknown"), locations);
  }

  /** 意图：服务器未声明任何能力时，请求前的能力判定必须为假而不是空指针。 */
  @Test
  void serverWithoutCapabilitiesAdvertisesNothing() throws Exception {
    LspClient client = start("no-capabilities");
    Path file = write("App.java", "class App {}\n");

    assertFalse(client.supports("textDocument/definition"));
    assertFalse(client.supports("workspace/symbol"));
    assertFalse(client.supports("java/classFileContents"));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> client.definition(file, 1, 0, REQUEST_TIMEOUT));
    assertTrue(error.getMessage().contains("does not advertise"), error.getMessage());
  }

  /** 意图：可执行文件无法执行时启动失败并给出命令与目录；不存在的文件在同步阶段被拒绝。 */
  @Test
  void unexecutableCommandAndMissingFileFailClearly() throws Exception {
    // 目录不可能被执行：启动必须失败并带上命令本身，而不是把目录当成服务器。
    Path notAProgram = root;
    LspServerConfig config =
        new LspServerConfig(
            "broken", List.of(notAProgram.toString()), List.of(".java"), List.of(), List.of());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> LspClient.launch(config, root, config.command().getFirst(), dispatch));
    assertTrue(error.getMessage().contains("cannot be started"), error.getMessage());
    assertTrue(error.getMessage().contains(notAProgram.toString()), error.getMessage());

    LspClient client = start("normal");
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> client.definition(root.resolve("missing.java"), 1, 0, REQUEST_TIMEOUT));
    assertTrue(missing.getMessage().contains("cannot read"), missing.getMessage());
  }

  /** 意图：只有时间戳变化（内容一致）时只刷新文档事实，不发送 didChange。 */
  @Test
  void unchangedContentDoesNotSendDidChange() throws Exception {
    LspClient client = start("normal");
    Path file = write("App.java", "class App {}\n");
    client.definition(file, 1, 0, REQUEST_TIMEOUT);

    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    client.definition(file, 1, 0, REQUEST_TIMEOUT);

    assertTrue(FakeLspServers.received(transcript, "textDocument/didChange").isEmpty());
    assertTrue(FakeLspServers.received(transcript, "textDocument/didSave").isEmpty());
    assertEquals(2, FakeLspServers.received(transcript, "textDocument/definition").size());
  }

  /** 意图：相对 class target 在没有 workdir 时被拒绝，绝不回退到守护进程 cwd，也不发出任何请求。 */
  @Test
  void relativeClassTargetWithoutWorkdirIsRejected() throws Exception {
    LspClient client = start("normal");
    Path file = write("App.java", "class App {}\n");
    Path classFile = Files.createDirectories(root.resolve("build")).resolve("App.class");
    Files.write(classFile, new byte[] {0x1});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> client.javaDecompile(null, "build/App.class", REQUEST_TIMEOUT));
    assertTrue(
        error.getMessage().contains("workdir is required when target is a relative class path"),
        error.getMessage());
    assertTrue(FakeLspServers.received(transcript, "workspace/executeCommand").isEmpty());

    // 绝对 class 路径不需要 workdir。
    assertEquals(
        FakeLspServer.DECOMPILED_SOURCE,
        client.javaDecompile(null, classFile.toString(), REQUEST_TIMEOUT));
    assertFalse(client.definition(file.toAbsolutePath(), 1, 0, REQUEST_TIMEOUT).isEmpty());
  }

  /** 意图：jdtls 识别覆盖包装脚本后缀与 Windows 路径分隔符。 */
  @Test
  void jdtlsCommandDetectionCoversWrappersAndSeparators() {
    assertTrue(LspClient.isJdtlsCommand(List.of("/opt/jdtls/bin/jdtls")));
    // 只有可执行文件基名是 jdtls 才算：jar 形态不是可靠信号。
    assertFalse(
        LspClient.isJdtlsCommand(List.of("java", "-jar", "/opt/jdtls/plugins/launcher.jar")));
    assertTrue(LspClient.isJdtlsCommand(List.of("C:\\tools\\jdtls.cmd")));
    assertTrue(LspClient.isJdtlsCommand(List.of("/opt/jdtls.exe", "-data", "/workspace")));
    assertFalse(LspClient.isJdtlsCommand(List.of("typescript-language-server", "--stdio")));
    assertFalse(LspClient.isJdtlsCommand(List.of()));
  }

  private LspClient start(String mode) throws Exception {
    LspServerConfig config = FakeLspServers.javaServer("fake", mode, root, transcript());
    LspClient client = LspClient.launch(config, root, config.command().getFirst(), dispatch);
    clients.add(client);
    client.initialize(INITIALIZE_TIMEOUT);
    return client;
  }

  private LspServerConfig holdingServer(Path pidFile) throws Exception {
    Path wrapper = root.resolve("hold-launch.sh");
    Files.writeString(
        wrapper,
        "#!/bin/sh\necho $$ > "
            + shellQuote(pidFile.toString())
            + "\nwhile true; do sleep 1; done\n");
    if (!wrapper.toFile().setExecutable(true)) {
      throw new IllegalStateException("cannot mark wrapper executable: " + wrapper);
    }
    return new LspServerConfig(
        "held", List.of(wrapper.toString()), List.of(".java"), List.of(), List.of());
  }

  private static long awaitPidFile(Path pidFile) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
        return Long.parseLong(Files.readString(pidFile).trim());
      }
      Thread.sleep(10);
    }
    throw new AssertionError("held launch did not record its pid: " + pidFile);
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private Path transcript() {
    if (transcript == null) {
      transcript = FakeLspServers.transcript(root);
    }
    return transcript;
  }

  /** 提交即拒绝，用来钉住 spawn 成功之后、所有权 try 之内的失败窗口。 */
  private static final class RejectingExecutor extends AbstractExecutorService {

    private volatile RuntimeException failure;

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("test dispatch rejects submissions");
    }
  }

  /** 接受任务后停在任务入口，直到测试释放；用于观察所有权窗口内进程仍然可回收。 */
  private static final class HoldingExecutor extends AbstractExecutorService {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final AtomicBoolean release = new AtomicBoolean();
    private volatile RuntimeException failure;

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      release.set(true);
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      Thread worker =
          new Thread(
              () -> {
                entered.countDown();
                while (!release.get()) {
                  try {
                    Thread.sleep(5);
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
                command.run();
              },
              "lsp-stderr-hold");
      worker.setDaemon(true);
      worker.start();
    }
  }

  private int startedProcesses() {
    int started = 0;
    for (JsonNode event : FakeLspServers.events(transcript)) {
      if (event.path("event").asText().equals("start")) {
        started++;
      }
    }
    return started;
  }

  private JsonNode awaitResponse(long id) {
    return FakeLspServers.await(
            transcript,
            event ->
                event.path("event").asText().equals("recv")
                    && event.path("message").path("method").isMissingNode()
                    && event.path("message").path("id").asLong() == id)
        .path("message");
  }

  private Path write(String name, String content) throws IOException {
    return Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
  }
}
