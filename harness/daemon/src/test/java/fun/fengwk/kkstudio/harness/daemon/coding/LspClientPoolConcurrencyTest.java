package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link LspClientPool} 的并发不变量：所有共享状态只在同一把锁内判定与迁移，阻塞操作在锁外完成。
 *
 * <p>用例用可控派发执行器精确制造三种窗口：暂停派发（观察“退休中 lease 必须等待而不是启动第二个实例”）、拒绝派发（观察同步兜底清理
 * 与关闭的逐个兜底）、启动未完成（观察关闭时的取消与进程终止）。全部断言基于真实假服务器进程与真实收发记录。
 */
class LspClientPoolConcurrencyTest {

  private static final Duration WAIT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService callers = Executors.newCachedThreadPool();
  private final List<LspClientPool> pools = new ArrayList<>();
  private final List<ExecutorService> dispatchers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    pools.forEach(LspClientPool::close);
    dispatchers.forEach(ExecutorService::shutdownNow);
    scheduler.shutdownNow();
    callers.shutdownNow();
  }

  /** 意图：同一 key 退休未完成前，并发 lease 必须在锁外等待，不得与旧实例并行启动第二个实例。 */
  @Test
  void leaseWaitsForRetirementBeforeStartingANewInstance() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    GateDispatch dispatch = gate();
    LspClientPool pool = pool("normal", transcript, Duration.ofMillis(100), dispatch);
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    LspClientPool.Client first = pool.lease(file);
    long firstPid = startedPids(transcript).get(0);
    pool.release(first);

    // 暂停派发：空闲检查仍会在 scheduler 上运行，于是退休登记可见，而真正阻塞的 stop 还停在队列里。
    dispatch.pause();
    awaitTrue(() -> pool.activeCount() == 0, WAIT);

    Future<LspClientPool.Client> second = callers.submit(() -> pool.lease(file));
    Thread.sleep(300);
    assertFalse(second.isDone(), "退休完成前 lease 不得返回");
    assertEquals(1, startedProcesses(transcript), "退休完成前不得启动第二个实例");

    dispatch.resume();
    LspClientPool.Client secondEntry = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(secondEntry.client().isAlive());
    awaitTrue(() -> startedProcesses(transcript) == 2, WAIT);
    FakeLspServers.awaitProcessGone(firstPid, Duration.ofSeconds(10));
    pool.release(secondEntry);
  }

  /**
   * 意图：stderr 派发被拒时，启动必须收敛整个执行范围并保留拒绝异常，池里不得留下未赋值的客户端。
   *
   * <p>命令与派发互斥：拒绝发生在命令真正运行之前时它根本没有机会产生副作用，发生在之后时它必须已经消失——两种合法结局都 要求范围收敛。
   */
  @Test
  void rejectedBootstrapDispatchTerminatesTheSpawnedProcess() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    Path pidFile = root.resolve("held.pid");
    GateDispatch dispatch = gate();
    dispatch.reject(true);
    LspServerConfig server = holdingServer(transcript, pidFile);
    LspClientPool pool = pool(transcript, Duration.ofMinutes(5), dispatch, server);
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    Future<Exception> pending =
        callers.submit(
            () -> {
              try {
                pool.lease(file);
                return null;
              } catch (Exception error) {
                return error;
              }
            });
    Exception error = pending.get(WAIT.toSeconds(), TimeUnit.SECONDS);

    assertTrue(error instanceof RejectedExecutionException, String.valueOf(error));
    assertConvergedCommand(pidFile);
    assertEquals(0, pool.activeCount(), "派发被拒的实例不得进入可用集合");
  }

  /** 意图：启动尚未完成时关闭：实例不得被发布，进程被终止，后续 lease 明确报已关闭。 */
  @Test
  void closeDuringBootCancelsPublicationAndStopsTheProcess() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspClientPool pool = pool("slow-init", transcript, Duration.ofSeconds(30), newDispatch());
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    Future<LspClientPool.Client> pending = callers.submit(() -> pool.lease(file));
    long pid = FakeLspServers.startedPid(transcript);
    pool.close();

    Exception error =
        assertThrows(Exception.class, () -> pending.get(WAIT.toSeconds(), TimeUnit.SECONDS));
    assertTrue(
        error.getMessage().contains("closed") || error.getMessage().contains("cancelled"),
        error.getMessage());
    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    assertEquals(0, pool.activeCount(), "未发布的实例不得进入可用集合");
    assertThrows(IllegalStateException.class, () -> pool.lease(file));
  }

  /** 意图：同一 key 的并发失败启动共享一次真实启动，失败登记被清理，后续调用可以重新尝试。 */
  @Test
  void concurrentFailedBootsShareOneLaunchAndCleanUpRegistration() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspClientPool pool = pool("dead", transcript, Duration.ofMinutes(5), newDispatch());
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    int callersCount = 4;
    CountDownLatch ready = new CountDownLatch(callersCount);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Exception>> results = new ArrayList<>();
    for (int index = 0; index < callersCount; index++) {
      results.add(
          callers.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  pool.lease(file);
                  return null;
                } catch (Exception error) {
                  return error;
                }
              }));
    }
    assertTrue(ready.await(WAIT.toSeconds(), TimeUnit.SECONDS), "并发调用方必须就绪");
    go.countDown();
    for (Future<Exception> result : results) {
      Exception error = result.get(WAIT.toSeconds(), TimeUnit.SECONDS);
      assertTrue(error != null, "死服务器上的 lease 必须失败");
    }

    assertEquals(1, startedProcesses(transcript), "并发失败启动只允许一次真实启动");
    assertEquals(0, pool.activeCount(), "失败实例不得残留在可用集合");
    // 失败登记已在锁内清理：下一次调用重新启动，而不是永久等待一个已完成的启动。
    assertThrows(Exception.class, () -> pool.lease(file));
    assertEquals(2, startedProcesses(transcript));
  }

  /** 意图：退休派发被拒时必须同步兜底停止旧实例并注销登记，后续 lease 不被永久阻塞。 */
  @Test
  void rejectedRetirementDispatchStillStopsTheClientAndFreesTheKey() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    GateDispatch dispatch = gate();
    LspClientPool pool = pool("normal", transcript, Duration.ofMillis(100), dispatch);
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    LspClientPool.Client first = pool.lease(file);
    long firstPid = startedPids(transcript).get(0);
    pool.release(first);

    dispatch.reject(true);
    awaitTrue(() -> pool.activeCount() == 0, WAIT);
    FakeLspServers.awaitProcessGone(firstPid, Duration.ofSeconds(10));
    dispatch.reject(false);

    Future<LspClientPool.Client> second = callers.submit(() -> pool.lease(file));
    LspClientPool.Client secondEntry = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(secondEntry.client().isAlive());
    assertEquals(2, startedProcesses(transcript));
    pool.release(secondEntry);
  }

  /** 意图：关闭时派发被拒也要逐个同步兜底，两个 key 的实例都必须停止。 */
  @Test
  void closeStopsEveryClientEvenWhenDispatchRejects() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    GateDispatch dispatch = gate();
    // 两个服务器的扩展名互斥，因此两个文件落在两个不同的复用键上。
    LspServerConfig template = FakeLspServers.javaServer("alpha", "normal", root, transcript);
    LspServerConfig alphaServer =
        new LspServerConfig(
            "alpha",
            template.command(),
            List.of(".java"),
            template.rootMarkers(),
            template.firstMatchMarkers());
    LspServerConfig betaServer =
        new LspServerConfig(
            "beta",
            template.command(),
            List.of(".txt"),
            template.rootMarkers(),
            template.firstMatchMarkers());
    LspClientPool pool = pool(transcript, Duration.ofMinutes(5), dispatch, alphaServer, betaServer);
    Path alpha = Files.writeString(root.resolve("Alpha.java"), "alpha\n");
    Path beta = Files.writeString(root.resolve("Beta.txt"), "beta\n");

    pool.release(pool.lease(alpha));
    pool.release(pool.lease(beta));
    List<Long> pids = startedPids(transcript);
    assertEquals(2, pids.size(), "两个 key 各有一个实例");

    dispatch.reject(true);
    pool.close();
    for (long pid : pids) {
      FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    }
    assertEquals(0, pool.activeCount());
  }

  /** 意图：空闲回收策略由配置决定：五分钟阈值在短时间内不回收，超过阈值才回收。 */
  @Test
  void idleEvictionHonoursTheConfiguredTimeout() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspClientPool slow = pool("normal", transcript, Duration.ofMinutes(5), newDispatch());
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    LspClientPool.Client entry = slow.lease(file);
    slow.release(entry);
    Thread.sleep(500);
    assertEquals(1, slow.activeCount(), "五分钟空闲阈值不得在短时间内回收");
    assertTrue(entry.client().isAlive());

    LspClientPool eager =
        pool(
            transcript,
            Duration.ofMillis(100),
            newDispatch(),
            FakeLspServers.javaServer("eager", "normal", root, transcript));
    LspClientPool.Client eagerEntry = eager.lease(file);
    eager.release(eagerEntry);
    awaitTrue(() -> eager.activeCount() == 0, WAIT);
    FakeLspServers.awaitProcessGone(startedPids(transcript).getLast(), Duration.ofSeconds(10));
  }

  /** 意图：只读探测不启动服务器；与回收竞争丢失的同步由下一次 lease 前的文档同步补齐，不会留下旧正文。 */
  @Test
  void noStartProbeNeverLaunchesAndLostRefreshIsRecoveredOnNextLease() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    LspClientPool pool = pool("normal", transcript, Duration.ofMillis(100), newDispatch());
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    assertTrue(pool.client(file).isEmpty(), "没有实例时探测只能返回空");
    assertEquals(0, startedProcesses(transcript), "只读探测不得启动服务器");

    LspClientPool.Client first = pool.lease(file);
    assertTrue(pool.client(file).isPresent());
    pool.release(first);
    awaitTrue(() -> pool.activeCount() == 0, WAIT);
    assertTrue(pool.client(file).isEmpty(), "退休中的实例不再被复用");
    assertEquals(1, startedProcesses(transcript), "探测与回收都不得启动新服务器");

    Files.writeString(file, "class App { void run() {} }\n");
    LspClientPool.Client second = pool.lease(file);
    second.client().definition(file, 1, 0, WAIT);
    pool.release(second);

    assertEquals(2, startedProcesses(transcript));
    assertEquals(
        "class App { void run() {} }\n",
        FakeLspServers.received(transcript, "textDocument/didOpen")
            .getLast()
            .path("params")
            .path("textDocument")
            .path("text")
            .asText(),
        "新实例必须在请求前同步磁盘上的当前正文");
  }

  /** 意图：退休已登记但派发被暂停（队列永远不会执行）时，close 自己必须接替完成——进程终止、lease 拒绝、且只发生一次真实 stop。 */
  @Test
  void closeFinishesPausedRetirementAndRejectsLease() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    GateDispatch dispatch = gate();
    LspClientPool pool = pool("normal", transcript, Duration.ofMillis(100), dispatch);
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");

    LspClientPool.Client first = pool.lease(file);
    long pid = startedPids(transcript).get(0);
    pool.release(first);
    dispatch.pause();
    awaitTrue(() -> pool.activeCount() == 0, WAIT);

    long startedAt = System.nanoTime();
    pool.close();
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(elapsedMillis < 5_000, "close 必须有界返回，实际 " + elapsedMillis + "ms");
    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    assertThrows(IllegalStateException.class, () -> pool.lease(file));

    // 队列恢复后不得再对同一个退休执行第二次真实 stop。
    dispatch.resume();
    Thread.sleep(300);
    assertEquals(1, FakeLspServers.received(transcript, "shutdown").size(), "同一个退休只允许一次真实 stop");
  }

  /** 意图：派发退休之后重新排期被 scheduler 拒绝时，既有退休仍必须被派发并停止；归还也不得把调度错误冒泡成工具错误。 */
  @Test
  void schedulerRejectionDoesNotSkipRegisteredRetirements() throws Exception {
    Path transcript = FakeLspServers.transcript(root);
    RejectingScheduler scheduler = rejectingScheduler();
    LspServerConfig template = FakeLspServers.javaServer("alpha", "normal", root, transcript);
    LspServerConfig alphaServer =
        new LspServerConfig(
            "alpha",
            template.command(),
            List.of(".java"),
            template.rootMarkers(),
            template.firstMatchMarkers());
    LspServerConfig betaServer =
        new LspServerConfig(
            "beta",
            template.command(),
            List.of(".txt"),
            template.rootMarkers(),
            template.firstMatchMarkers());
    LspClientPool pool =
        new LspClientPool(
            FakeLspServers.discovery(alphaServer, betaServer),
            scheduler,
            newDispatch(),
            Duration.ofMillis(800),
            Duration.ofMillis(500),
            Duration.ofSeconds(3));
    pools.add(pool);
    Path alphaFile = Files.writeString(root.resolve("Alpha.java"), "alpha\n");
    Path betaFile = Files.writeString(root.resolve("Beta.txt"), "beta\n");

    LspClientPool.Client alpha = pool.lease(alphaFile);
    long alphaPid = startedPids(transcript).get(0);
    pool.release(alpha);
    Thread.sleep(200);
    LspClientPool.Client beta = pool.lease(betaFile);
    pool.release(beta);

    // alpha 的截止时刻触发回收；回收后重新排期必然被拒，但不能因此漏掉已登记的退休。
    scheduler.reject(true);
    awaitTrue(() -> pool.activeCount() == 1, WAIT);
    FakeLspServers.awaitProcessGone(alphaPid, Duration.ofSeconds(10));

    pool.release(pool.lease(betaFile));
    assertEquals(1, pool.activeCount(), "仍存活的实例不因排期被拒而消失");
  }

  private LspClientPool pool(
      String mode, Path transcript, Duration idleTimeout, ExecutorService dispatch) {
    return pool(
        transcript,
        idleTimeout,
        dispatch,
        FakeLspServers.javaServer("fake", mode, root, transcript));
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

  private GateDispatch gate() {
    GateDispatch dispatch = new GateDispatch();
    dispatchers.add(dispatch);
    return dispatch;
  }

  private ExecutorService newDispatch() {
    ExecutorService dispatch = Executors.newCachedThreadPool();
    dispatchers.add(dispatch);
    return dispatch;
  }

  private LspServerConfig holdingServer(Path transcript, Path pidFile) throws Exception {
    LspServerConfig template = FakeLspServers.javaServer("held", "normal", root, transcript);
    Path wrapper = root.resolve("hold-bootstrap.sh");
    Files.writeString(
        wrapper,
        "#!/bin/sh\necho $$ > "
            + shellQuote(pidFile.toString())
            + "\nwhile true; do sleep 1; done\n");
    if (!wrapper.toFile().setExecutable(true)) {
      throw new IllegalStateException("cannot mark wrapper executable: " + wrapper);
    }
    Files.writeString(root.resolve("pom.xml"), "<project/>\n");
    return new LspServerConfig(
        template.id(),
        List.of(wrapper.toString()),
        template.extensions(),
        template.rootMarkers(),
        template.firstMatchMarkers());
  }

  /**
   * 启动失败返回之后，命令要么从未跑起来（pid 文件没有被写下），要么已经消失。
   *
   * <p>判断发生在 launch 返回之后，而当时范围已经收敛，因此不可能再有「稍后才出现」的进程：文件不存在就说明命令没有机会执行。
   */
  private static void assertConvergedCommand(Path pidFile) {
    long pid = readPidQuietly(pidFile);
    if (pid > 0) {
      FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    }
  }

  private static long readPidQuietly(Path pidFile) {
    try {
      if (!Files.isRegularFile(pidFile)) {
        return -1;
      }
      String content = Files.readString(pidFile).trim();
      return content.isEmpty() ? -1 : Long.parseLong(content);
    } catch (Exception error) {
      return -1;
    }
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static int startedProcesses(Path transcript) {
    return startedPids(transcript).size();
  }

  private static List<Long> startedPids(Path transcript) {
    List<Long> pids = new ArrayList<>();
    for (var event : FakeLspServers.events(transcript)) {
      if (event.path("event").asText().equals("start")) {
        pids.add(event.path("pid").asLong());
      }
    }
    return pids;
  }

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

  private RejectingScheduler rejectingScheduler() {
    RejectingScheduler scheduler = new RejectingScheduler();
    dispatchers.add(scheduler);
    return scheduler;
  }

  /** 可控调度器：置位后拒绝新的排期，用于验证「先清理、后排期」的顺序。 */
  private static final class RejectingScheduler extends ScheduledThreadPoolExecutor {

    private volatile boolean rejecting;

    private RejectingScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      if (rejecting) {
        throw new RejectedExecutionException("test scheduler rejects scheduling");
      }
      return super.schedule(command, delay, unit);
    }

    private void reject(boolean value) {
      rejecting = value;
    }
  }

  /** 可控派发执行器：暂停时排队，拒绝时抛 {@link RejectedExecutionException}，用于精确制造两种窗口。 */
  private static final class GateDispatch extends ThreadPoolExecutor {

    private final List<Runnable> queued = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean paused;
    private volatile boolean rejecting;

    private GateDispatch() {
      super(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
      allowCoreThreadTimeOut(true);
    }

    @Override
    public void execute(Runnable command) {
      if (rejecting) {
        throw new RejectedExecutionException("test dispatch rejects submissions");
      }
      if (paused) {
        queued.add(command);
        return;
      }
      super.execute(command);
    }

    private void pause() {
      paused = true;
    }

    private void reject(boolean value) {
      rejecting = value;
    }

    private void resume() {
      paused = false;
      List<Runnable> drained;
      synchronized (queued) {
        drained = new ArrayList<>(queued);
        queued.clear();
      }
      for (Runnable task : drained) {
        super.execute(task);
      }
    }
  }
}
