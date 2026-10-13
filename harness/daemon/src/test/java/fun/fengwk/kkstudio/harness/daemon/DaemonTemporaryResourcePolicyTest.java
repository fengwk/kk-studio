package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.coding.TextOutputStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 意图：临时资源策略是保留期与扫描间隔的唯一归属点——通告后按最新策略真正回收过期 workspace，清扫失败只收敛为诊断并让后续清扫继续，关闭后不再调度。 */
class DaemonTemporaryResourcePolicyTest {

  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

  @TempDir Path root;

  private ScheduledExecutorService scheduler;
  private ExecutorService tasks;

  @BeforeEach
  void setUp() {
    scheduler = Executors.newSingleThreadScheduledExecutor();
    tasks = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
    tasks.shutdownNow();
  }

  /** 无受控存储（测试构造）时只记录策略事实，不调度也不报错。 */
  @Test
  void recordsPolicyWithoutControlledStorage() {
    DaemonTemporaryResourcePolicy policy =
        new DaemonTemporaryResourcePolicy(scheduler, tasks, null);

    policy.apply(120L, 30L);
    assertEquals(120L, policy.ttlSeconds());
    assertEquals(30L, policy.cleanupIntervalSeconds());

    policy.close();
    policy.close();
  }

  /** 通告保留期与扫描间隔后，定时清扫真的回收「已登记、超期且未在用」的 workspace。 */
  @Test
  void scheduledSweepReclaimsExpiredWorkspace() throws Exception {
    TextOutputStore store = TextOutputStore.open(root.resolve("tmp"));
    DaemonTemporaryResourcePolicy policy =
        new DaemonTemporaryResourcePolicy(scheduler, tasks, store);
    Path expired = registeredWorkspace(store, -3600L);

    policy.apply(1L, 1L);

    awaitTrue(() -> !Files.exists(expired));
    // 同一间隔再次通告不重排调度，但策略事实仍以最新通告为准。
    policy.apply(1L, 1L);
    assertEquals(1L, policy.cleanupIntervalSeconds());
    policy.close();
  }

  /** 调度器已停机（关闭竞态）时应用策略只记录事实，绝不把拒绝调度升级为失败。 */
  @Test
  void recordsPolicyWhenSchedulerIsAlreadyShutDown() throws Exception {
    TextOutputStore store = TextOutputStore.open(root.resolve("tmp"));
    DaemonTemporaryResourcePolicy policy =
        new DaemonTemporaryResourcePolicy(scheduler, tasks, store);
    scheduler.shutdownNow();
    tasks.shutdownNow();

    policy.apply(5L, 3L);

    assertEquals(5L, policy.ttlSeconds());
    assertEquals(3L, policy.cleanupIntervalSeconds());
    policy.close();
  }

  /** 清扫派发被拒（任务执行器停机竞态）只忽略这一次派发：周期性清扫绝不因此被取消。 */
  @Test
  void rejectedSweepDispatchDoesNotCancelLaterSweeps() throws Exception {
    TextOutputStore store = TextOutputStore.open(root.resolve("tmp"));
    RejectFirstDispatchExecutor rejecting = new RejectFirstDispatchExecutor();
    DaemonTemporaryResourcePolicy policy =
        new DaemonTemporaryResourcePolicy(scheduler, rejecting, store);
    Path expired = registeredWorkspace(store, -3600L);
    try {
      policy.apply(1L, 1L);

      awaitTrue(() -> !Files.exists(expired));
      assertTrue(rejecting.rejected, "first dispatch must have been rejected");
    } finally {
      policy.close();
      rejecting.shutdownNow();
    }
  }

  /** 首次派发抛 {@link RejectedExecutionException}（模拟停机竞态），之后正常委派。 */
  private static final class RejectFirstDispatchExecutor extends AbstractExecutorService {

    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean first = new AtomicBoolean(true);
    private volatile boolean rejected;

    @Override
    public void execute(Runnable command) {
      if (first.getAndSet(false)) {
        rejected = true;
        throw new RejectedExecutionException("task executor is shutting down");
      }
      delegate.execute(command);
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }

  /**
   * 清扫失败只收敛为安全诊断：受控根被破坏时失败的清扫绝不中断后续周期清扫。
   *
   * <p>这是原始实现（异常逃逸会取消周期性任务）与拆分后行为的可观察差异。
   */
  @Test
  void sweepFailureDoesNotStopLaterSweeps() throws Exception {
    TextOutputStore store = TextOutputStore.open(root.resolve("tmp"));
    DaemonTemporaryResourcePolicy policy =
        new DaemonTemporaryResourcePolicy(scheduler, tasks, store);

    policy.apply(1L, 1L);
    // 删除受控根使接下来的清扫必然失败。
    deleteRecursively(store.root());
    Thread.sleep(1500L);

    // 重建受控根与过期 workspace，并改变间隔触发重排：清扫必须仍然有效。
    Files.createDirectories(store.root());
    Path expired = registeredWorkspace(store, -3600L);
    policy.apply(1L, 2L);

    awaitTrue(() -> !Files.exists(expired));
    policy.close();
  }

  /** 在受控根下造一个「名字是 UUID 且带合法 created-at 登记」的 workspace。 */
  private static Path registeredWorkspace(TextOutputStore store, long ageSeconds) throws Exception {
    Path workspace = store.root().resolve(UUID.randomUUID().toString());
    Files.createDirectories(workspace);
    Files.writeString(
        workspace.resolve("created-at"),
        Long.toString(Instant.now().getEpochSecond() + ageSeconds));
    return workspace;
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50L);
    }
    assertTrue(condition.getAsBoolean(), "condition did not become true within " + AWAIT_TIMEOUT);
  }

  private static void deleteRecursively(Path path) throws Exception {
    if (!Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    }
    assertFalse(Files.exists(path));
  }
}
