package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 生产目录对账器的调度契约测试：唤醒合并、stop/start 世代边界与启动失败回滚。
 *
 * <p>权威仓储被门控（每次读取可阻塞在指定闩上），因此并发唤醒、在途对账与停止的时序都是确定断言，不用任意 sleep。
 */
class PluginCatalogRefresherTest {

  private static final Duration INTERVAL = Duration.ofMinutes(30);

  @Test
  void activeRejectionIsReportedAndStoppedWakesAreNoOps() throws InterruptedException {
    PluginCatalogRuntime runtime = mock(PluginCatalogRuntime.class);
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    when(executor.awaitTermination(anyLong(), any())).thenReturn(true);
    doThrow(new RejectedExecutionException("active rejection"))
        .when(executor)
        .execute(any(Runnable.class));
    try (PluginCatalogRefresher refresher =
        new PluginCatalogRefresher(runtime, INTERVAL, () -> executor)) {
      refresher.requestRefresh();
      refresher.start();
      refresher.start();
      assertThrows(RejectedExecutionException.class, refresher::requestRefresh);
      assertThrows(RejectedExecutionException.class, refresher::requestRefresh);
      verify(executor, times(2)).execute(any(Runnable.class));
      refresher.stop();
      refresher.requestRefresh();
    }
  }

  @Test
  void rejectsInvalidIntervals() {
    PluginCatalogRuntime runtime = mock(PluginCatalogRuntime.class);
    for (Duration interval :
        List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
      assertThrows(
          IllegalArgumentException.class, () -> new PluginCatalogRefresher(runtime, interval));
    }
  }

  /** 并发唤醒只合并成至多一个待执行对账，且运行期到达的每个唤醒都不丢：每次进入权威读取的数量即是证据。 */
  @Test
  void coalescesConcurrentWakesIntoOneQueuedRefresh() {
    GatedRepository repository = new GatedRepository();
    PluginCatalogRuntime runtime = runtime(repository);
    try {
      PluginCatalogRefresher refresher = new PluginCatalogRefresher(runtime, INTERVAL);
      try {
        refresher.start();
        awaitReads(repository, 1);

        CountDownLatch runningEntered = new CountDownLatch(1);
        CountDownLatch runningRelease = new CountDownLatch(1);
        repository.gate(runningEntered, runningRelease);
        refresher.requestRefresh();
        await(runningEntered);
        assertEquals(1, repository.reads());
        assertEquals(1, repository.blockedReads());

        // 对账已在途（标志已清）后到达的三个唤醒只应合并成一个待执行对账。
        refresher.requestRefresh();
        refresher.requestRefresh();
        refresher.requestRefresh();
        CountDownLatch queuedEntered = new CountDownLatch(1);
        CountDownLatch queuedRelease = new CountDownLatch(1);
        repository.gate(queuedEntered, queuedRelease);
        runningRelease.countDown();
        await(queuedEntered);
        assertEquals(
            2, repository.reads(), "exactly one coalesced refresh must follow the running one");
        assertEquals(
            2, repository.blockedReads(), "no second refresh may slip past the coalescing");

        CountDownLatch lastEntered = new CountDownLatch(1);
        CountDownLatch lastRelease = new CountDownLatch(1);
        repository.gate(lastEntered, lastRelease);
        // 运行中到达的唤醒必须再排一次：既不无界排队，也不丢唤醒。
        refresher.requestRefresh();
        refresher.requestRefresh();
        queuedRelease.countDown();
        await(lastEntered);
        assertEquals(3, repository.reads());
        lastRelease.countDown();
        awaitReads(repository, 4);
        assertNoFurtherRead(repository, "wakes must never accumulate beyond one queued refresh");
      } finally {
        refresher.close();
      }
    } finally {
      runtime.close();
    }
  }

  /** stop 只停止调度：在途对账既不被打断也不被提前回收，已排队未开始的对账在 stop 后不再执行 SQL。 */
  @Test
  void stopLeavesTheRunningRefreshAloneAndDropsQueuedOnes() {
    GatedRepository repository = new GatedRepository();
    PluginCatalogRuntime runtime = runtime(repository);
    try {
      PluginCatalogRefresher refresher = new PluginCatalogRefresher(runtime, INTERVAL);
      try {
        refresher.start();
        awaitReads(repository, 1);

        CountDownLatch runningEntered = new CountDownLatch(1);
        CountDownLatch runningRelease = new CountDownLatch(1);
        repository.gate(runningEntered, runningRelease);
        refresher.requestRefresh();
        await(runningEntered);
        // 第二个唤醒已排入 executor 队列，但尚未开始。
        refresher.requestRefresh();

        Thread stopper = new Thread(refresher::stop);
        stopper.start();
        awaitNotRunning(refresher);
        assertThrows(
            IllegalStateException.class,
            refresher::start,
            "restart must be refused while the previous executor is still running");
        assertNoFurtherRead(repository, "a stopped refresher must not queue new work");

        runningRelease.countDown();
        try {
          stopper.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          fail(error);
        }
        assertFalse(stopper.isAlive(), "stop must return once the in-flight refresh finishes");
        assertEquals(2, repository.reads(), "the queued refresh must not run SQL after stop");
        assertEquals(0, repository.interruptions(), "an in-flight refresh must not be interrupted");
        assertFalse(refresher.isRunning());

        // 上一代退出后重启：新世代只会由新唤醒驱动。
        refresher.start();
        assertTrue(refresher.isRunning());
        awaitReads(repository, 3);
      } finally {
        refresher.close();
      }
    } finally {
      runtime.close();
    }
  }

  /** 调度失败必须回滚 running 状态并关闭新建的 executor，绝不留半启动的一代。 */
  @Test
  void startFailureRollsBackRunningAndClosesTheCreatedExecutor() {
    GatedRepository repository = new GatedRepository();
    PluginCatalogRuntime runtime = runtime(repository);
    try {
      ScheduledExecutorService broken = mock(ScheduledExecutorService.class);
      when(broken.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
          .thenThrow(new RejectedExecutionException("rejected"));
      when(broken.isTerminated()).thenReturn(false, true);
      AtomicInteger created = new AtomicInteger();
      Supplier<ScheduledExecutorService> factory =
          () ->
              created.getAndIncrement() == 0
                  ? broken
                  : Executors.newSingleThreadScheduledExecutor(
                      Thread.ofPlatform()
                          .name("plugin-catalog-refresh-test")
                          .daemon(true)
                          .factory());

      PluginCatalogRefresher refresher = new PluginCatalogRefresher(runtime, INTERVAL, factory);
      try {
        assertThrows(RejectedExecutionException.class, refresher::start);
        assertFalse(refresher.isRunning(), "a failed start must not leave the refresher running");
        verify(broken).shutdownNow();
        assertEquals(0, repository.reads());
        assertThrows(
            IllegalStateException.class,
            refresher::start,
            "failed-start cleanup must finish before another generation may start");

        refresher.start();
        awaitReads(repository, 1);
        assertTrue(
            refresher.isRunning(), "a later start must be able to build a healthy generation");
      } finally {
        refresher.close();
      }
    } finally {
      runtime.close();
    }
  }

  /** close 是幂等且彻底的：停止调度、拒绝再启动、后续唤醒即使被调用也不再触碰权威。 */
  @Test
  void closeConvergesTheExecutorAndRefusesRestart() {
    GatedRepository repository = new GatedRepository();
    PluginCatalogRuntime runtime = runtime(repository);
    try {
      PluginCatalogRefresher refresher = new PluginCatalogRefresher(runtime, INTERVAL);
      try {
        refresher.start();
        awaitReads(repository, 1);

        refresher.close();
        refresher.close();
        refresher.stop();
        assertFalse(refresher.isRunning());
        assertThrows(IllegalStateException.class, refresher::start);

        refresher.requestRefresh();
        assertNoFurtherRead(repository, "a closed refresher must never reconcile again");
        assertEquals(1, repository.reads());
      } finally {
        refresher.close();
      }
    } finally {
      runtime.close();
    }
  }

  /** 真实运行期（空安装集合）：构造期自身会回读一次，因此计数从装配完成后开始。 */
  private static PluginCatalogRuntime runtime(GatedRepository repository) {
    PluginCatalogRuntime runtime =
        new PluginCatalogRuntime(
            new StudioPluginRegistry(List.of()),
            repository,
            pluginId -> {
              throw new AssertionError("no plugin is installed");
            },
            List.of(),
            List.of());
    repository.resetReads();
    return runtime;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS), "the gated refresh must reach the authority");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      fail(error);
    }
  }

  private static void awaitReads(GatedRepository repository, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (repository.reads() < expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, repository.reads());
  }

  private static void awaitNotRunning(PluginCatalogRefresher refresher) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (refresher.isRunning() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertFalse(refresher.isRunning(), "stop must stop scheduling promptly");
  }

  /** 断言之后没有新的权威读取：门控读取若发生会阻塞并被观察，因此这是确定性负向断言而非 sleep。 */
  private static void assertNoFurtherRead(GatedRepository repository, String message) {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    repository.gate(entered, release);
    try {
      assertFalse(entered.await(300, TimeUnit.MILLISECONDS), message);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      fail(error);
    } finally {
      release.countDown();
    }
  }

  /** 可门控的权威仓储：每次读取都可先阻塞在测试持有的闩上，并记录读取、阻塞与被中断的次数。 */
  private static final class GatedRepository extends PluginConfigurationRepository {

    private final AtomicInteger reads = new AtomicInteger();

    private final AtomicInteger blockedReads = new AtomicInteger();

    private final AtomicInteger interruptions = new AtomicInteger();

    private volatile CountDownLatch entered;

    private volatile CountDownLatch release;

    GatedRepository() {
      super(mock(JdbcClient.class));
    }

    /** 之后每次读取都先 countDown {@code entered} 再等待 {@code release}。 */
    void gate(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    int reads() {
      return reads.get();
    }

    /** 以装配后的基线为零：启动初始化自身的回读不参与对账次数断言。 */
    void resetReads() {
      reads.set(0);
    }

    int blockedReads() {
      return blockedReads.get();
    }

    int interruptions() {
      return interruptions.get();
    }

    @Override
    public List<PluginConfigurationRow> findAll() {
      CountDownLatch gateEntered = entered;
      CountDownLatch gateRelease = release;
      if (gateEntered != null && gateRelease != null) {
        blockedReads.incrementAndGet();
        gateEntered.countDown();
        try {
          gateRelease.await();
        } catch (InterruptedException error) {
          interruptions.incrementAndGet();
          Thread.currentThread().interrupt();
          throw new IllegalStateException("gated configuration read was interrupted", error);
        }
      }
      reads.incrementAndGet();
      return List.of();
    }
  }
}
