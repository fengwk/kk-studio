package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * {@link LspService} 的生命周期与失败收敛：初始化中途关闭、崩溃后重建、配置校验与关闭后的拒绝服务。
 *
 * <p>这些用例验证的是"异常路径也要收敛"：不能因为关闭、崩溃或非法参数留下进程、悬挂的等待者或可用的半初始化实例。
 */
class LspServiceLifecycleTest {

  @TempDir Path root;

  private final ExecutorService lspDispatch = Executors.newCachedThreadPool();
  private final ScheduledExecutorService lspScheduler =
      Executors.newSingleThreadScheduledExecutor();
  private final List<LspService> services = new ArrayList<>();

  @AfterEach
  void tearDown() {
    services.forEach(LspService::close);
    lspScheduler.shutdownNow();
    lspDispatch.shutdownNow();
  }

  /** 意图：初始化进行中关闭服务时，等待者收到明确错误，半初始化进程立即被收敛。 */
  @Test
  void closeDuringBootCancelsTheStartupAndTerminatesTheProcess() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service("slow-init", transcript, Duration.ofSeconds(30));
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      Future<String> pending =
          caller.submit(() -> service.gotoDefinition(file, 1, 0, Duration.ofSeconds(30)));
      // 等待服务器真的启动（初始化尚未返回）后再关闭。
      FakeLspServers.await(transcript, event -> event.path("event").asText().equals("start"));
      service.close();

      Exception error = assertThrows(Exception.class, pending::get);
      assertTrue(
          error.getMessage().contains("closed") || error.getMessage().contains("cancelled"),
          error.getMessage());
      FakeLspServers.awaitProcessGone(
          FakeLspServers.startedPid(transcript), Duration.ofSeconds(10));
      assertThrows(
          IllegalStateException.class,
          () -> service.gotoDefinition(file, 1, 0, Duration.ofSeconds(5)));
    } finally {
      caller.shutdownNow();
    }
  }

  /** 意图：客户端进程崩溃后，下一次调用会重建实例而不是复用死连接。 */
  @Test
  void crashedClientIsRebuiltOnTheNextCall() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service("normal", transcript, Duration.ofSeconds(5));
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    service.gotoDefinition(file, 1, 0, Duration.ofSeconds(20));
    long firstPid = FakeLspServers.startedPid(transcript);

    // 直接杀掉服务器进程，模拟崩溃：池必须在下次调用时发现它不可用。
    ProcessHandle.of(firstPid).orElseThrow().destroyForcibly();
    FakeLspServers.awaitProcessGone(firstPid, Duration.ofSeconds(10));

    assertEquals(file + ":10:5", service.gotoDefinition(file, 1, 0, Duration.ofSeconds(20)));
    assertEquals(2, startedProcesses(transcript), "崩溃后必须重新启动而不是复用死连接");
    assertEquals(1, service.activeClientCount());
  }

  /** 意图：没有在途请求的文件不会在同步时启动服务器；未配置的扩展名也不会。 */
  @Test
  void fileChangedAndSupportNeverBootWithoutAConfiguredServer() {
    Path transcript = FakeLspServers.transcript(root);
    LspService service = service("normal", transcript, Duration.ofSeconds(5));
    Path file = root.resolve("App.java");

    service.fileChanged(file);
    assertTrue(service.support(file).supported(), "配置命中");
    assertEquals(0, service.activeClientCount());
    assertTrue(FakeLspServers.events(transcript).isEmpty());

    LspService unconfigured = LspService.create(LspDiscovery.empty(), lspDispatch, lspScheduler);
    services.add(unconfigured);
    unconfigured.fileChanged(file);
    assertTrue(unconfigured.support(file).language().isEmpty());
  }

  /** 意图：非法时间参数在装配期失败关闭，而不是在运行期表现为悬挂。 */
  @Test
  void rejectsNonPositiveLifecycleDurations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LspService.create(
                LspDiscovery.empty(),
                lspDispatch,
                lspScheduler,
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LspService.create(
                LspDiscovery.empty(),
                lspDispatch,
                lspScheduler,
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LspService.create(
                LspDiscovery.empty(),
                lspDispatch,
                lspScheduler,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ZERO));
  }

  /** 意图：未安装的服务器不会启动进程，错误信息指向命令本身。 */
  @Test
  void notInstalledServerFailsWithoutStartingAProcess() {
    LspService service =
        LspService.create(
            LspDiscovery.of(
                List.of(
                    new LspServerConfig(
                        "missing-ls",
                        List.of("/nope/missing-ls"),
                        List.of(".java"),
                        List.of(),
                        List.of()))),
            lspDispatch,
            lspScheduler);
    services.add(service);
    Path file = root.resolve("App.java");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.gotoDefinition(file, 1, 0, Duration.ofSeconds(5)));
    assertTrue(error.getMessage().contains("is not installed"), error.getMessage());
    assertEquals(0, service.activeClientCount());
  }

  /** 意图：并发等待者共享同一次失败启动，不会各自重试出多个进程。 */
  @Test
  void concurrentWaitersShareTheSameFailedBoot() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspServerConfig dead = FakeLspServers.javaServer("dead", "dead", root, transcript);
    LspService service =
        LspService.create(
            FakeLspServers.discovery(dead),
            lspDispatch,
            lspScheduler,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5));
    services.add(service);
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService callers = Executors.newFixedThreadPool(4);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int index = 0; index < 4; index++) {
        results.add(
            callers.submit(
                () -> {
                  start.await();
                  try {
                    service.gotoDefinition(file, 1, 0, Duration.ofSeconds(5));
                    return false;
                  } catch (IllegalStateException expected) {
                    return true;
                  }
                }));
      }
      start.countDown();
      for (Future<Boolean> result : results) {
        assertTrue(result.get(), "启动失败必须传递给所有等待者");
      }
      assertEquals(1, startedProcesses(transcript), "失败启动不重试出多个进程");
    } finally {
      callers.shutdownNow();
    }
  }

  private LspService service(String mode, Path transcript, Duration idleTimeout) {
    LspService service =
        LspService.create(
            FakeLspServers.discovery(FakeLspServers.javaServer("fake", mode, root, transcript)),
            lspDispatch,
            lspScheduler,
            idleTimeout,
            Duration.ofMillis(300),
            Duration.ofSeconds(30));
    services.add(service);
    return service;
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
