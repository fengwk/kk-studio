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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 自有物理资源的静止证据：真实服务器进程、进程范围状态目录与客户端自有 IO 任务在启动失败、任务取消与关闭之后都必须真正收敛。
 *
 * <p>用例用真实执行器精确制造三种窗口：启动失败发生在 stderr 任务已经开始之后、自有任务停在队列里从未开始、自有任务被外部取消。 断言只读真实假服务器记录、真实
 * pid、临时目录里的状态目录数量与执行器观察到的任务退出，不插入测试专用的生产钩子。
 */
class LspClientQuiescenceTest {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<Thread> callerThreads = new CopyOnWriteArrayList<>();
  private final ExecutorService callers =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "lsp-quiescence-caller");
            callerThreads.add(thread);
            return thread;
          });
  private final List<ExecutorService> dispatchers = new ArrayList<>();
  private final List<LspClient> clients = new ArrayList<>();
  private final List<LspClientPool> pools = new ArrayList<>();

  private Error expectedCloseFailure;

  @AfterEach
  void tearDown() {
    try {
      assertAll(
          () ->
              assertAll(
                  pools.stream()
                      .map(
                          pool ->
                              () -> {
                                if (expectedCloseFailure == null) {
                                  pool.close();
                                } else {
                                  assertSame(
                                      expectedCloseFailure, assertThrows(Error.class, pool::close));
                                }
                              })),
          () ->
              assertAll(clients.stream().map(client -> () -> client.stop(Duration.ofMillis(200)))));
    } finally {
      List<ExecutorService> executors = new ArrayList<>(dispatchers);
      executors.add(scheduler);
      executors.add(callers);
      executors.forEach(ExecutorService::shutdownNow);
      for (ExecutorService executor : executors) {
        try {
          assertTrue(
              executor.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS),
              "the owned executor must terminate");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          fail("interrupted while waiting for executor termination");
        }
      }
    }
  }

  /**
   * 意图：启动失败发生在 stderr 任务已经开始之后时，这次启动已经提交的自有任务必须退出，进程与状态目录必须收敛。
   *
   * <p>同步：启动在调用线程之外异步进行，测试线程先等到 stderr 任务真正开始运行、致命提交已经进入闸门，再从真实 transcript 读到子进程自己写下的 pid，
   * 之后才放行注入那条致命错误。「失败之前进程真的存在过」因此是观测事实而不是时序假设，同一 pid 消失才是回收证据。
   */
  @Test
  void launchFailureAfterTheStderrTaskStartedStillStopsEveryOwnedResource() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    prepareRoot();
    CountDownLatch gate = new CountDownLatch(1);
    TrackingDispatch dispatch = tracking(2, gate);
    LspServerConfig server = FakeLspServers.javaServer("quiescence", "normal", root, transcript);
    long stateDirectories = countStateDirectories();
    Future<Throwable> launching = callers.submit(() -> launchFailure(server, dispatch));
    try {
      assertTrue(dispatch.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "致命提交必须进入等待闸门");
      dispatch.awaitTaskStarted(1, WAIT);

      long pid = FakeLspServers.startedPid(transcript);
      gate.countDown();

      Throwable error = awaitFailure(launching);
      assertSame(dispatch.fatal, error, "启动失败必须保持原身份");
      dispatch.assertOwnedTasksExited(WAIT);
      FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
      assertEquals(stateDirectories, countStateDirectories(), "启动失败不得留下进程范围状态目录");
    } finally {
      gate.countDown();
      // 故障路径下调用线程可能仍停在闸门上：有界结算启动结论，避免把在途启动留给 tearDown。
      try {
        launching.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      } catch (ExecutionException | TimeoutException ignored) {
        // 启动结论由用例主体断言，这里只保证启动线程已经结算。
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * 意图：关闭时仍在握手的启动实例已经登记在案，必须由关闭直接停止它，而不是等它自己的初始化超时。
   *
   * <p>判据是关闭耗时：初始化超时设为 20s，同步点在启动线程真正阻塞于 {@code LspClient.initialize} 时，因此「直接停止」必须在远小于超时的有界时间内返回。
   */
  @Test
  void closeStopsTheClientOfAnInFlightBootInsteadOfWaitingForItsTimeout() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    prepareRoot();
    LspClientPool pool =
        pool(
            Duration.ofMinutes(5),
            Duration.ofMillis(500),
            Duration.ofSeconds(20),
            newDispatch(),
            FakeLspServers.javaServer("slow-init", "slow-init", root, transcript));
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    long stateDirectories = countStateDirectories();

    Future<LspClientPool.Client> pending = callers.submit(() -> pool.lease(file));
    long pid = FakeLspServers.startedPid(transcript);
    // 同步点：实例已经构造并登记（登记发生在握手之前），关闭因此可以立即接管它。
    awaitBlockedIn(callerThreads, LspClient.class.getName(), "initialize", WAIT);

    long startedAt = System.nanoTime();
    pool.close();
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    Exception error =
        assertThrows(Exception.class, () -> pending.get(WAIT.toSeconds(), TimeUnit.SECONDS));
    assertTrue(
        error.getMessage().contains("closed") || error.getMessage().contains("cancelled"),
        error.getMessage());
    assertTrue(elapsedMillis < 5_000, "关闭必须直接停止在途启动，实际 " + elapsedMillis + "ms");
    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    assertEquals(0, pool.activeCount(), "未完成的启动不得进入可用集合");
    assertEquals(stateDirectories, countStateDirectories(), "关闭之后不得留下进程范围状态目录");
    assertThrows(IllegalStateException.class, () -> pool.lease(file));
  }

  /**
   * 意图：关闭先停止可用实例，再逐个等启动收敛；即使另一个 key 的启动最终以致命失败结束，可用实例也必须已经被收回，且关闭报告同一条终结 Error。
   *
   * <p>同步点是关闭线程真正阻塞在生产 {@code awaitBootCompletion} 上时，第一个 key 的进程已经消失：这既证明关闭没有跳过健康实例，
   * 也证明它没有把「等启动失败」当成「完成」。致命启动失败对那个 key 是终结事实，因此关闭不能宣称成功。
   */
  @Test
  void closeStopsTheHealthyClientWhileAnotherKeyStartupFailsFatally() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    prepareRoot();
    CountDownLatch gate = new CountDownLatch(1);
    TrackingDispatch dispatch = tracking(0, gate);
    expectedCloseFailure = dispatch.fatal;
    LspServerConfig alpha = server("alpha", ".java", transcript);
    LspServerConfig beta = server("beta", ".txt", transcript);
    LspClientPool pool =
        pool(
            Duration.ofMinutes(5),
            Duration.ofMillis(500),
            Duration.ofSeconds(3),
            dispatch,
            alpha,
            beta);
    Path alphaFile = Files.writeString(root.resolve("Alpha.java"), "alpha\n");
    Path betaFile = Files.writeString(root.resolve("Beta.txt"), "beta\n");
    long stateDirectories = countStateDirectories();
    try {
      pool.release(pool.lease(alphaFile));
      long alphaPid = awaitStartedProcesses(transcript, 1, WAIT).getFirst();

      dispatch.fatalOnSubmit(dispatch.submitCount() + 1);
      Future<Throwable> creator = callers.submit(() -> leaseFailure(pool, betaFile));
      assertTrue(dispatch.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "第二个 key 必须进入启动窗口");
      long betaPid = awaitStartedProcesses(transcript, 2, WAIT).getLast();

      Future<?> closing = callers.submit(pool::close);
      awaitBlockedInAwaitBootCompletion(callerThreads, WAIT);
      // 关闭还在等第二个 key 的启动结论时，第一个 key 的实例就必须已经停止。
      FakeLspServers.awaitProcessGone(alphaPid, Duration.ofSeconds(10));
      assertFalse(closing.isDone(), "启动还没有给出收敛结论时 close 不得声称完成");

      gate.countDown();
      ExecutionException closeFailure =
          assertThrows(
              ExecutionException.class, () -> closing.get(WAIT.toSeconds(), TimeUnit.SECONDS));

      assertSame(dispatch.fatal, closeFailure.getCause(), "关闭必须报告同一条终结 Error");
      assertSame(dispatch.fatal, awaitFailure(creator), "致命失败必须交给创建者并保持身份");
      FakeLspServers.awaitProcessGone(betaPid, Duration.ofSeconds(10));
      assertEquals(stateDirectories, countStateDirectories(), "关闭之后不得留下进程范围状态目录");
      assertThrows(IllegalStateException.class, () -> pool.lease(alphaFile));
    } finally {
      gate.countDown();
    }
  }

  /**
   * 意图：监听任务停在外部队列里从未开始时，停止必须结算它并保持外部执行器可用，进程与状态目录照常收敛。
   *
   * <p>「被结算的任务永不执行运行体」由 {@link LspClientOwnedTasksTest} 直接证明；这里证明它在客户端生命周期上的结果： 停止既不失败，也不关闭
   * DaemonRuntime 拥有的执行器。
   */
  @Test
  void clientStopKeepsTheRuntimeExecutorActiveWhenTheListenerNeverRan() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    prepareRoot();
    QueuedListenerDispatch dispatch = queuedListenerDispatch();
    LspServerConfig server =
        FakeLspServers.javaServer("queued-listener", "normal", root, transcript);
    long stateDirectories = countStateDirectories();
    LspClient client =
        LspClient.launch(server, root, server.command().getFirst(), dispatch, () -> true);
    clients.add(client);
    long pid = FakeLspServers.startedPid(transcript);

    client.stop(Duration.ofMillis(200));

    assertFalse(client.isAlive());
    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    assertEquals(stateDirectories, countStateDirectories(), "停止之后不得留下进程范围状态目录");
    assertTrue(dispatch.resumeAndProbe(WAIT), "自有登记绝不允许关闭外部执行器");
  }

  private LspClientPool pool(
      Duration idleTimeout,
      Duration grace,
      Duration initializeTimeout,
      ExecutorService dispatch,
      LspServerConfig... servers) {
    LspClientPool pool =
        new LspClientPool(
            FakeLspServers.discovery(servers),
            scheduler,
            dispatch,
            idleTimeout,
            grace,
            initializeTimeout);
    pools.add(pool);
    return pool;
  }

  private ExecutorService newDispatch() {
    ExecutorService dispatch = Executors.newCachedThreadPool();
    dispatchers.add(dispatch);
    return dispatch;
  }

  private LspClient launch(LspServerConfig server, ExecutorService dispatch) throws Exception {
    LspClient client =
        LspClient.launch(server, root, server.command().getFirst(), dispatch, () -> true);
    clients.add(client);
    client.initialize(INITIALIZE_TIMEOUT);
    return client;
  }

  /** 工作区根标记：没有它时服务器配置无法确定项目根。 */
  private void prepareRoot() throws IOException {
    Files.writeString(root.resolve("pom.xml"), "<project/>\n");
  }

  private LspServerConfig server(String id, String extension, Path transcript) {
    LspServerConfig template = FakeLspServers.javaServer(id, "normal", root, transcript);
    return new LspServerConfig(
        id,
        template.command(),
        List.of(extension),
        template.rootMarkers(),
        template.firstMatchMarkers());
  }

  private TrackingDispatch tracking(int fatalSubmit, CountDownLatch gate) {
    TrackingDispatch dispatch = new TrackingDispatch(fatalSubmit, gate);
    dispatchers.add(dispatch);
    return dispatch;
  }

  private QueuedListenerDispatch queuedListenerDispatch() {
    QueuedListenerDispatch dispatch = new QueuedListenerDispatch();
    dispatchers.add(dispatch);
    return dispatch;
  }

  private static Throwable leaseFailure(LspClientPool pool, Path file) {
    try {
      pool.lease(file);
      return null;
    } catch (Throwable error) {
      return error;
    }
  }

  /** 有界收集启动结论：致命失败原样返回，成功则登记实例供收尾释放，绝不吞掉失败。 */
  private Throwable launchFailure(LspServerConfig server, ExecutorService dispatch) {
    try {
      LspClient client =
          LspClient.launch(server, root, server.command().getFirst(), dispatch, () -> true);
      clients.add(client);
      return null;
    } catch (Throwable error) {
      return error;
    }
  }

  /** 有界收集：共享启动必须在给定时间内交付，否则是明确的失败而不是让测试悬挂。 */
  private static Throwable awaitFailure(Future<Throwable> result) throws Exception {
    return result.get(WAIT.toSeconds(), TimeUnit.SECONDS);
  }

  /** 等待关闭线程真正进入生产 awaitBootCompletion：这是「关闭正在等启动收敛」的唯一同步依据。 */
  private static void awaitBlockedInAwaitBootCompletion(List<Thread> threads, Duration timeout)
      throws InterruptedException {
    awaitBlockedIn(threads, LspClientPool.class.getName(), "awaitBootCompletion", timeout);
  }

  /** 等待某个生产方法在调用线程栈上出现：不依赖 sleep 猜测同步点。 */
  private static void awaitBlockedIn(
      List<Thread> threads, String className, String method, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (blockedIn(threads, className, method)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("no thread ever reached " + className + "." + method);
  }

  private static boolean blockedIn(List<Thread> threads, String className, String method) {
    for (Thread thread : threads) {
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals(className) && frame.getMethodName().equals(method)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 轮询直到条件成立；用于等待空闲回收这类由调度器触发的状态迁移。 */
  private static void awaitTrue(Supplier<Boolean> condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("condition was not met within " + timeout);
  }

  /** 轮询启动记录直到出现足够多的真实启动，并返回它们的 pid。 */
  private static List<Long> awaitStartedProcesses(Path transcript, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      List<Long> pids = startedPids(transcript);
      if (pids.size() >= expected) {
        return pids;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("expected " + expected + " started processes in " + transcript);
  }

  private static List<Long> startedPids(Path transcript) {
    List<Long> pids = new ArrayList<>();
    for (JsonNode event : FakeLspServers.events(transcript)) {
      if (event.path("event").asString().equals("start")) {
        pids.add(event.path("pid").asLong());
      }
    }
    return pids;
  }

  /** 统计临时目录里的进程范围状态目录数量，用于断言失败与关闭路径都不留残留。 */
  private static long countStateDirectories() throws IOException {
    try (Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return entries
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith(ProcessScope.STATE_DIR_PREFIX))
          .count();
    }
  }

  /**
   * 真实线程池：记录每个被受理任务真正开始与结束的时刻，并可以让第 N 次提交在任务运行之前抛出固定的合成 {@link AssertionError}。
   *
   * <p>它让测试可以精确制造「已经有自有 IO 任务在跑时的启动失败」与「启动失败发生在关闭已经等待收敛之后」的窗口，
   * 并据此断言这些任务真的退出了。致命提交在抛错之前等待测试放行（{@code gate}），测试线程因此可以先读到真实子进程写下的证据，再放行注入同一条致命错误。
   */
  private static final class TrackingDispatch extends ThreadPoolExecutor {

    final AssertionError fatal = new AssertionError("synthetic LSP startup failure");
    final CountDownLatch entered = new CountDownLatch(1);

    private final CountDownLatch gate;
    private final AtomicInteger submitted = new AtomicInteger();
    private final List<CountDownLatch> starts = Collections.synchronizedList(new ArrayList<>());
    private final List<CountDownLatch> exits = Collections.synchronizedList(new ArrayList<>());
    private volatile int fatalSubmit;

    private TrackingDispatch(int fatalSubmit, CountDownLatch gate) {
      super(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
      allowCoreThreadTimeOut(true);
      this.fatalSubmit = fatalSubmit;
      this.gate = gate;
    }

    @Override
    public void execute(Runnable command) {
      int index = submitted.incrementAndGet();
      if (index == fatalSubmit) {
        entered.countDown();
        awaitGate();
        throw fatal;
      }
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch exit = new CountDownLatch(1);
      starts.add(start);
      exits.add(exit);
      super.execute(
          () -> {
            start.countDown();
            try {
              command.run();
            } finally {
              exit.countDown();
            }
          });
    }

    /** 让指定次序的提交抛出致命错误；测试用 {@link #submitCount()} 计算确切的次序。 */
    private void fatalOnSubmit(int index) {
      fatalSubmit = index;
    }

    private int submitCount() {
      return submitted.get();
    }

    /** 有界等待第 {@code index}（从 1 开始）个自有任务真正开始运行：受理记录不等于已经开始。 */
    private void awaitTaskStarted(int index, Duration timeout) throws InterruptedException {
      CountDownLatch startedTask;
      synchronized (starts) {
        if (starts.size() < index) {
          fail("owned LSP I/O task #" + index + " was never dispatched");
        }
        startedTask = starts.get(index - 1);
      }
      if (!startedTask.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        fail("an owned LSP I/O task did not start running");
      }
    }

    /** 有界断言：已经提交的每一个自有任务都真正结束了。 */
    private void assertOwnedTasksExited(Duration timeout) throws InterruptedException {
      long deadline = System.nanoTime() + timeout.toNanos();
      List<CountDownLatch> pending;
      synchronized (exits) {
        pending = new ArrayList<>(exits);
      }
      for (CountDownLatch exit : pending) {
        if (!exit.await(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS)) {
          fail("an owned LSP I/O task did not exit");
        }
      }
    }

    private void awaitGate() {
      try {
        gate.await();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted before injecting the synthetic failure", error);
      }
    }
  }

  /** 只把第二次提交（lsp4j 监听任务）停在队列里：stderr 排空任务照常执行，监听永远不开始。 */
  private static final class QueuedListenerDispatch extends ThreadPoolExecutor {

    private final AtomicInteger submitted = new AtomicInteger();
    private final List<Runnable> queued = Collections.synchronizedList(new ArrayList<>());

    private QueuedListenerDispatch() {
      super(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
      allowCoreThreadTimeOut(true);
    }

    @Override
    public void execute(Runnable command) {
      if (submitted.incrementAndGet() == 2) {
        queued.add(command);
        return;
      }
      super.execute(command);
    }

    /** 恢复队列并跑一个探测任务：外部执行器仍然可用时它必须被执行。 */
    private boolean resumeAndProbe(Duration timeout) throws InterruptedException {
      List<Runnable> drained = new ArrayList<>(queued);
      queued.clear();
      for (Runnable task : drained) {
        super.execute(task);
      }
      CountDownLatch probe = new CountDownLatch(1);
      super.execute(probe::countDown);
      return probe.await(timeout.toSeconds(), TimeUnit.SECONDS);
    }
  }
}
