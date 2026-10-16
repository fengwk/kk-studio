package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 启动阶段致命失败（{@link Error}）的收尾：已拥有的真实进程范围必须收敛，共享启动的等待者必须有界返回，失败登记必须清理。
 *
 * <p>失败由真实执行器在启动派发上抛出固定的合成 {@link AssertionError}：不伪造客户端、不使用 OOM，也不插入测试专用的生产钩子。
 */
class LspClientStartFailureTest {

  private static final Duration WAIT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<Thread> callerThreads = new CopyOnWriteArrayList<>();
  private final ExecutorService callers =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "lsp-start-failure-caller");
            callerThreads.add(thread);
            return thread;
          });
  private final List<ExecutorService> dispatchers = new ArrayList<>();
  private final List<LspClientPool> pools = new ArrayList<>();

  @AfterEach
  void tearDown() {
    try {
      assertAll(pools.stream().map(pool -> pool::close));
    } finally {
      List<ExecutorService> executors = new ArrayList<>(dispatchers);
      executors.add(scheduler);
      executors.add(callers);
      executors.forEach(ExecutorService::shutdownNow);
      assertAll(
          executors.stream()
              .map(
                  executor ->
                      () ->
                          assertTrue(
                              executor.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                              "the owned executor must terminate")));
    }
  }

  /** 意图：启动派发上的致命 Error 必须收敛已经拥有的真实进程，并以原身份交给调用方。 */
  @Test
  void launchDispatchFatalErrorReapsTheOwnedProcess() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    FatalDispatch dispatch = fatalDispatch(transcript, false);
    LspServerConfig server = FakeLspServers.javaServer("fatal", "normal", root, transcript);

    try {
      Error error =
          assertThrows(
              AssertionError.class,
              () ->
                  LspClient.launch(
                      server, root, server.command().getFirst(), dispatch, () -> true));

      assertSame(dispatch.fatal, error, "致命失败必须保持原身份");
      FakeLspServers.awaitProcessGone(dispatch.observedPid, Duration.ofSeconds(10));
    } finally {
      dispatch.release.countDown();
      reapOwnedProcess(dispatch.observedProcess);
    }
  }

  /** 意图：同 key 的多个等待者共享一次致命失败启动，都以原身份有界返回；登记清理后同一 key 可以重试成功。 */
  @Test
  void sharedFatalBootDeliversToEveryWaiterAndCanRetry() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    FatalDispatch dispatch = fatalDispatch(transcript, true);
    LspClientPool pool =
        pool(
            transcript,
            Duration.ofMinutes(5),
            dispatch,
            FakeLspServers.javaServer("fatal", "normal", root, transcript));
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    int waiters = 3;
    try {
      Future<Throwable> creator = callers.submit(() -> leaseFailure(pool, file));
      assertTrue(dispatch.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "启动派发必须进入所有权窗口");
      List<Future<Throwable>> waiterResults = new ArrayList<>();
      for (int index = 0; index < waiters; index++) {
        waiterResults.add(callers.submit(() -> leaseFailure(pool, file)));
      }
      // 同步点：等待者必须真正阻塞在生产 waitForBoot 上，而不是靠 sleep 猜测它们已经就位。
      awaitBlockedInWaitForBoot(callerThreads, waiters, WAIT);

      dispatch.release.countDown();

      assertSame(dispatch.fatal, awaitFailure(creator), "创建者必须拿到原 Error 身份");
      for (Future<Throwable> result : waiterResults) {
        assertSame(dispatch.fatal, awaitFailure(result), "等待者必须共享同一个 Error");
      }
      assertEquals(1, startedProcesses(transcript), "并发失败启动只允许一次真实启动");
      FakeLspServers.awaitProcessGone(dispatch.observedPid, Duration.ofSeconds(10));

      // 失败登记已在锁内清理：同一 key 重新启动，而不是永久等待一个已完成的启动。
      LspClientPool.Client retried = pool.lease(file);
      assertTrue(retried.client().isAlive(), "清理后重试必须启动出存活实例");
      assertEquals(2, startedProcesses(transcript), "重试必须真的重新启动");
      pool.release(retried);
    } finally {
      dispatch.release.countDown();
      reapOwnedProcess(dispatch.observedProcess);
    }
  }

  /** 关闭与迟到的启动失败并发：关闭必须在有界等待里等到这次启动的收敛结论，不能提前声称完成；创建者仍然收到原身份的致命失败，且不留进程、状态目录与自有任务。 */
  @Test
  void closeWaitsForTheLateFatalStartupAndStillDeliversItToTheCreator() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    FatalDispatch dispatch = fatalDispatch(transcript, true);
    LspClientPool pool =
        pool(
            transcript,
            Duration.ofMinutes(5),
            dispatch,
            FakeLspServers.javaServer("late-fatal", "normal", root, transcript));
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    long stateDirectories = countStateDirectories();
    try {
      Future<Throwable> creator = callers.submit(() -> leaseFailure(pool, file));
      assertTrue(dispatch.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));
      Future<?> closing = callers.submit(pool::close);
      // 同步点：关闭线程必须真正阻塞在生产 awaitBootCompletion 上，而不是靠 sleep 猜测它已经开始收尾。
      awaitBlockedInAwaitBootCompletion(callerThreads, WAIT);
      assertFalse(closing.isDone(), "启动还没有给出收敛结论时 close 不得声称完成");

      dispatch.release.countDown();
      closing.get(WAIT.toSeconds(), TimeUnit.SECONDS);

      assertSame(dispatch.fatal, awaitFailure(creator), "迟到的致命失败必须交给创建者并保持身份");
      FakeLspServers.awaitProcessGone(dispatch.observedPid, Duration.ofSeconds(10));
      assertEquals(stateDirectories, countStateDirectories(), "启动失败与关闭都不允许留下状态目录");
      assertThrows(IllegalStateException.class, () -> pool.lease(file));
      assertEquals(1, startedProcesses(transcript));
    } finally {
      dispatch.release.countDown();
      reapOwnedProcess(dispatch.observedProcess);
    }
  }

  private FatalDispatch fatalDispatch(Path transcript, boolean gated) {
    FatalDispatch dispatch = new FatalDispatch(transcript, gated);
    dispatchers.add(dispatch);
    return dispatch;
  }

  private LspClientPool pool(
      Path transcript, Duration idleTimeout, ExecutorService dispatch, LspServerConfig... servers) {
    LspClientPool pool =
        new LspClientPool(
            FakeLspServers.discovery(servers),
            scheduler,
            dispatch,
            idleTimeout,
            Duration.ofMillis(500),
            Duration.ofSeconds(3));
    pools.add(pool);
    return pool;
  }

  private static Throwable leaseFailure(LspClientPool pool, Path file) {
    try {
      pool.lease(file);
      return null;
    } catch (Throwable error) {
      return error;
    }
  }

  /** 有界收集：共享启动必须在给定时间内交付，否则是明确的失败而不是让测试悬挂。 */
  private static Throwable awaitFailure(Future<Throwable> result) throws Exception {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      if (result.isDone()) {
        return result.get();
      }
      Thread.sleep(10);
    }
    return fail("the shared boot must deliver its failure within " + WAIT);
  }

  /** 等待指定数量的调用线程真正进入生产 waitForBoot：读取线程栈，不依赖 sleep 猜测同步点。 */
  private static void awaitBlockedInWaitForBoot(
      List<Thread> threads, int expected, Duration timeout) throws InterruptedException {
    awaitBlockedIn(threads, "waitForBoot", expected, timeout);
  }

  /** 等待关闭线程真正进入生产 awaitBootCompletion：这是「关闭正在等启动收敛」的唯一同步依据。 */
  private static void awaitBlockedInAwaitBootCompletion(List<Thread> threads, Duration timeout)
      throws InterruptedException {
    awaitBlockedIn(threads, "awaitBootCompletion", 1, timeout);
  }

  private static void awaitBlockedIn(
      List<Thread> threads, String method, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (blockedIn(threads, method) >= expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "expected "
            + expected
            + " pool threads blocked in "
            + method
            + ", saw "
            + blockedIn(threads, method));
  }

  private static int blockedIn(List<Thread> threads, String method) {
    int blocked = 0;
    for (Thread thread : threads) {
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals(LspClientPool.class.getName())
            && frame.getMethodName().equals(method)) {
          blocked++;
          break;
        }
      }
    }
    return blocked;
  }

  /** 统计临时目录里的进程范围状态目录数量，用于断言启动失败与关闭都不留残留。 */
  private static long countStateDirectories() throws IOException {
    try (Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return entries
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith(ProcessScope.STATE_DIR_PREFIX))
          .count();
    }
  }

  private static int startedProcesses(Path transcript) {
    int started = 0;
    for (JsonNode event : FakeLspServers.events(transcript)) {
      if (event.path("event").asString().equals("start")) {
        started++;
      }
    }
    return started;
  }

  /** 仅收回本测试确实观察到的进程，避免被测代码的失败路径在负向验证时留下进程。 */
  private static void reapOwnedProcess(ProcessHandle process) {
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
      FakeLspServers.awaitProcessGone(process.pid(), Duration.ofSeconds(10));
    }
  }

  /**
   * 真实线程池：在首次启动派发上先确认真实服务器进程已经启动，再抛出固定的合成 {@link AssertionError}。
   *
   * <p>{@code gated} 为真时抛错前等待测试释放闩锁，让测试线程先观察到等待者进入生产 {@code waitForBoot}；此后它恢复为普通执行器，供清理后的重试启动使用。
   */
  private static final class FatalDispatch extends ThreadPoolExecutor {

    final AssertionError fatal = new AssertionError("synthetic LSP startup failure");
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    private final Path transcript;
    private final boolean gated;
    private final AtomicBoolean fatalPending = new AtomicBoolean(true);
    private volatile long observedPid;
    private volatile ProcessHandle observedProcess;

    private FatalDispatch(Path transcript, boolean gated) {
      super(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
      allowCoreThreadTimeOut(true);
      this.transcript = transcript;
      this.gated = gated;
    }

    @Override
    public void execute(Runnable command) {
      if (!fatalPending.compareAndSet(true, false)) {
        super.execute(command);
        return;
      }
      entered.countDown();
      long pid = FakeLspServers.startedPid(transcript);
      ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
      observedProcess = process;
      observedPid = pid;
      if (!process.isAlive()) {
        throw new AssertionError(
            "the owned server process must exist before the synthetic failure");
      }
      if (gated) {
        awaitRelease();
      }
      throw fatal;
    }

    private void awaitRelease() {
      try {
        release.await();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted before injecting the synthetic failure", error);
      }
    }
  }
}
