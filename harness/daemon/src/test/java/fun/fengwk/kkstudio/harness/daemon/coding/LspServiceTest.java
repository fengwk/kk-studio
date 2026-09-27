package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/**
 * {@link LspService} 的门面契约：复用、并发初始化去重、闲置回收、关闭收敛，以及 read/write 两个接入点。
 *
 * <p>每个用例都使用真实子进程的 {@link FakeLspServer}，因此"复用与回收"是进程级事实而不是 mock 计数。
 */
class LspServiceTest {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path root;

  /** LSP 客户端的阻塞 stdio 与空闲回收计时器在生产由 Daemon 拥有；测试里由本类自己持有并关闭。 */
  private final ExecutorService lspDispatch = Executors.newCachedThreadPool();

  private final ScheduledExecutorService lspScheduler =
      Executors.newSingleThreadScheduledExecutor();
  private final List<LspService> services = new ArrayList<>();

  @AfterEach
  void closeServices() {
    services.forEach(LspService::close);
    lspScheduler.shutdownNow();
    lspDispatch.shutdownNow();
  }

  /** 意图：同一项目根的连续调用复用同一个客户端进程，不重复启动。 */
  @Test
  void reusesTheClientAcrossCalls() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service(transcript, LspService.DEFAULT_IDLE_TIMEOUT);
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    service.gotoDefinition(file, 1, 0, REQUEST_TIMEOUT);
    long pidBefore = FakeLspServers.startedPid(transcript);
    service.gotoDefinition(file, 1, 0, REQUEST_TIMEOUT);

    assertEquals(1, service.activeClientCount());
    assertEquals(1, startedProcesses(transcript));
    assertEquals(pidBefore, FakeLspServers.startedPid(transcript));
  }

  /** 意图：同一 key 的并发首次调用只启动一次，其余等待者复用同一次初始化。 */
  @Test
  void deduplicatesConcurrentBoots() throws Exception {
    // 初始化 3 秒才返回，保证并发窗口真实存在。
    Path slowRoot = Files.createDirectories(root.resolve("slow-init"));
    Path slowTranscript = FakeLspServers.transcript(slowRoot);

    LspService slowService =
        LspService.create(
            FakeLspServers.discovery(
                FakeLspServers.javaServer("slow", "slow-init", slowRoot, slowTranscript)),
            lspDispatch,
            lspScheduler,
            LspService.DEFAULT_IDLE_TIMEOUT,
            Duration.ofSeconds(2),
            Duration.ofSeconds(30));
    services.add(slowService);
    Path file = Files.createDirectories(slowRoot.resolve("src")).resolve("App.java");
    Files.writeString(file, "class App {}\n");

    ExecutorService callers = Executors.newFixedThreadPool(6);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> results = new ArrayList<>();
      for (int index = 0; index < 6; index++) {
        results.add(
            callers.submit(
                () -> {
                  start.await();
                  return slowService.gotoDefinition(file, 1, 0, Duration.ofSeconds(30));
                }));
      }
      start.countDown();
      for (Future<String> result : results) {
        assertTrue(result.get().contains("App.java:10:5"), "并发调用都必须成功");
      }
      assertEquals(1, startedProcesses(slowTranscript), "并发初始化必须被合并成一次真实启动");
      assertEquals(1, slowService.activeClientCount());
    } finally {
      callers.shutdownNow();
    }
  }

  /** 意图：没有在途请求且闲置超时后客户端被回收，进程随之退出；随后再次调用会重新启动。 */
  @Test
  void reclaimsIdleClients() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service(transcript, Duration.ofMillis(300));
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    service.gotoDefinition(file, 1, 0, REQUEST_TIMEOUT);
    long pid = FakeLspServers.startedPid(transcript);

    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    assertEquals(0, service.activeClientCount(), "闲置客户端必须被回收");

    service.gotoDefinition(file, 1, 0, REQUEST_TIMEOUT);
    assertEquals(2, startedProcesses(transcript), "回收后再次调用会重新启动服务器");
  }

  /** 意图：close 幂等且收敛：所有客户端进程都被终止，之后的调用明确失败。 */
  @Test
  void closeStopsEveryClientAndIsIdempotent() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service(transcript, LspService.DEFAULT_IDLE_TIMEOUT);
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    service.gotoDefinition(file, 1, 0, REQUEST_TIMEOUT);
    long pid = FakeLspServers.startedPid(transcript);

    service.close();
    service.close();

    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    assertEquals(0, service.activeClientCount());
    assertThrows(
        IllegalStateException.class, () -> service.gotoDefinition(file, 1, 0, REQUEST_TIMEOUT));
    assertTrue(FakeLspServers.received(transcript, "shutdown").size() >= 1);
  }

  /** 意图：write/edit 提交后的同步刷新已打开文档，并且不启动任何新进程。 */
  @Test
  void fileChangedRefreshesOnlyOpenedDocuments() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service(transcript, LspService.DEFAULT_IDLE_TIMEOUT);
    Path opened = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path untouched = Files.writeString(root.resolve("Other.java"), "class Other {}\n");

    // 未打开过的文件：不得因此启动服务器。
    service.fileChanged(untouched);
    assertEquals(0, service.activeClientCount());

    service.gotoDefinition(opened, 1, 0, REQUEST_TIMEOUT);
    Files.writeString(opened, "class App { int value; }\n");
    service.fileChanged(opened);

    // 通知没有应答，因此等待服务器真的收到它，而不是依赖写入时机。
    JsonNode changed =
        FakeLspServers.await(transcript, event -> "textDocument/didChange".equals(methodOf(event)))
            .path("message");
    assertEquals(
        "class App { int value; }\n",
        changed.path("params").path("contentChanges").get(0).path("text").asText());
    assertEquals(1, startedProcesses(transcript));
  }

  /** 意图：read 侧判定不启动任何进程，也不抛异常。 */
  @Test
  void supportNeverBootsAServer() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service(transcript, LspService.DEFAULT_IDLE_TIMEOUT);

    Path java = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path text = Files.writeString(root.resolve("notes.md"), "# notes\n");

    LspSupport supported = service.support(java);
    assertTrue(supported.supported());
    assertTrue(supported.available(), "假服务器命令是本机 JDK");
    assertEquals("fake", supported.language().orElseThrow());
    assertFalse(service.support(text).supported());
    assertEquals(0, service.activeClientCount());
    assertTrue(FakeLspServers.events(transcript).isEmpty(), "只读判定不得启动服务器");
  }

  /** 意图：未配置服务器时的工具调用给出可操作错误，而不是静默失败。 */
  @Test
  void unconfiguredLanguageFailsWithActionableMessage() {
    LspService service = LspService.create(LspDiscovery.empty(), lspDispatch, lspScheduler);
    services.add(service);
    Path file = root.resolve("App.java");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> service.gotoDefinition(file, 1, 0, REQUEST_TIMEOUT));
    assertTrue(error.getMessage().contains("No LSP server configured"), error.getMessage());
  }

  /** 意图：复用键包含项目根，因此同一服务为不同仓库各启动一个实例。 */
  @Test
  void differentWorkspaceRootsUseDifferentClients() throws Exception {
    Path firstRepo = Files.createDirectories(root.resolve("repo-a"));
    Files.createDirectories(firstRepo.resolve(".git"));
    Files.writeString(firstRepo.resolve("pom.xml"), "<project/>");
    Path secondRepo = Files.createDirectories(root.resolve("repo-b"));
    Files.createDirectories(secondRepo.resolve(".git"));
    Files.writeString(secondRepo.resolve("pom.xml"), "<project/>");
    Path firstFile = Files.createDirectories(firstRepo.resolve("src")).resolve("App.java");
    Path secondFile = Files.createDirectories(secondRepo.resolve("src")).resolve("App.java");
    Files.writeString(firstFile, "class App {}\n");
    Files.writeString(secondFile, "class App {}\n");

    Path transcript = FakeLspServers.transcript(root);
    LspService service = service(transcript, LspService.DEFAULT_IDLE_TIMEOUT);

    service.gotoDefinition(firstFile, 1, 0, REQUEST_TIMEOUT);
    service.gotoDefinition(secondFile, 1, 0, REQUEST_TIMEOUT);

    assertEquals(2, service.activeClientCount());
    assertEquals(2, startedProcesses(transcript));
    // 每个根各自收到了初始化，且各自只初始化一次。
    assertEquals(2, FakeLspServers.received(transcript, "initialize").size());
  }

  private LspService service(Path transcript, Duration idleTimeout) {
    LspService service =
        LspService.create(
            FakeLspServers.discovery(FakeLspServers.javaServer("fake", "normal", root, transcript)),
            lspDispatch,
            lspScheduler,
            idleTimeout,
            Duration.ofMillis(500),
            Duration.ofSeconds(30));
    services.add(service);
    return service;
  }

  private static String methodOf(JsonNode event) {
    return event.path("message").path("method").asText("");
  }

  private static int startedProcesses(Path transcript) {
    int started = 0;
    for (var event : FakeLspServers.events(transcript)) {
      if (event.path("event").asText().equals("start")) {
        started++;
      }
    }
    return started;
  }
}
