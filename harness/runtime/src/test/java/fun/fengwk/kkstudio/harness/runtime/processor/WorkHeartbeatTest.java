package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** WorkHeartbeat 线程安全测试：可控 fake scheduler 确定性验证 start/stop 交错后不会留下未 cancel 的 periodic task。 */
class WorkHeartbeatTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));

  @Test
  void startThenStopCancelsScheduledTask() {
    Fixture fixture = new Fixture();
    assertTrue(fixture.heartbeat.start(fixture.claimed));
    assertEquals(1, fixture.scheduler.scheduled.size());

    fixture.heartbeat.stop();

    assertTrue(fixture.scheduler.scheduled.get(0).isCancelled());
    assertFalse(fixture.heartbeat.start(fixture.claimed));
  }

  @Test
  void stopBeforeStartPreventsScheduling() {
    Fixture fixture = new Fixture();
    fixture.heartbeat.stop();

    assertFalse(fixture.heartbeat.start(fixture.claimed));
    assertTrue(fixture.scheduler.scheduled.isEmpty());
  }

  @Test
  void startIsAtMostOnce() {
    Fixture fixture = new Fixture();
    assertTrue(fixture.heartbeat.start(fixture.claimed));
    assertFalse(fixture.heartbeat.start(fixture.claimed));
    assertEquals(1, fixture.scheduler.scheduled.size());
  }

  /** 并发交错 start/stop：任何成功提交的 periodic task 最终都必然被 stop cancel（无泄漏不变量）。 */
  @Test
  void concurrentStartStopLeavesNoUncancelledScheduledTask() throws Exception {
    Fixture fixture = new Fixture();
    int threads = 8;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    AtomicInteger starts = new AtomicInteger();
    for (int i = 0; i < threads; i++) {
      boolean wantStart = i % 2 == 0;
      executor.submit(
          () -> {
            ready.countDown();
            go.await();
            if (wantStart && fixture.heartbeat.start(fixture.claimed)) {
              starts.incrementAndGet();
            } else if (!wantStart) {
              fixture.heartbeat.stop();
            }
            return null;
          });
    }
    ready.await();
    go.countDown();
    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(starts.get(), fixture.scheduler.scheduled.size());
    for (FakeScheduledFuture future : fixture.scheduler.scheduled) {
      assertTrue(future.isCancelled(), "every scheduled heartbeat task must be cancelled");
    }
  }

  /**
   * beat 与 stop 同锁互斥：在途 beat（renew 事务被 store monitor 阻塞）期间 stop 必须等待；stop 返回后手动再触发 periodic task
   * 也绝不再 renew lease。
   */
  @Test
  void stopWaitsForInFlightBeatAndNoRenewAfterStopReturns() throws Exception {
    Fixture fixture = new Fixture();
    assertTrue(fixture.heartbeat.start(fixture.claimed));
    Runnable beat = fixture.scheduler.periodicTasks.get(0);

    // 一个线程持 store monitor（事务回调内阻塞），使 beat 的 renew 事务卡在锁外。
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holdStore =
        new Thread(
            () ->
                fixture.store.transaction(
                    ignored -> {
                      holding.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    holdStore.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS));

    // 在途 beat：已进入 beat（持 WorkHeartbeat 锁）并阻塞在 store 事务等待。
    Thread beatThread = new Thread(beat);
    beatThread.start();
    assertFalse(fixture.scheduler.scheduled.get(0).isCancelled());
    Thread.sleep(100);

    AtomicBoolean stopReturned = new AtomicBoolean();
    Thread stopThread =
        new Thread(
            () -> {
              fixture.heartbeat.stop();
              stopReturned.set(true);
            });
    stopThread.start();
    Thread.sleep(100);
    assertFalse(stopReturned.get(), "stop must block while a beat is in flight");

    release.countDown();
    holdStore.join(5000);
    beatThread.join(5000);
    stopThread.join(5000);
    assertTrue(stopReturned.get(), "stop returns once the in-flight beat finished");
    assertTrue(fixture.scheduler.scheduled.get(0).isCancelled());

    // stop 返回后：periodic task 即使被手动触发也绝不再 renew lease。
    Instant before = leaseUntil(fixture);
    beat.run();
    assertEquals(before, leaseUntil(fixture));
  }

  /** renew 失败（work 行被删）：锁内标记停止并 cancel，lost ownership 通知在锁外执行且只发生一次。 */
  @Test
  void beatFailureMarksStoppedAndNotifiesLostOwnershipOnce() {
    Fixture fixture = new Fixture();
    AtomicInteger notifications = new AtomicInteger();
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            fixture.store,
            fixture.scheduler,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            notifications::incrementAndGet);
    assertTrue(heartbeat.start(fixture.claimed));

    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.claimed.target().id()).orElseThrow();
          tx.deleteWork(fixture.claimed.target());
          return null;
        });
    Runnable beat = fixture.scheduler.periodicTasks.get(0);
    beat.run();

    assertEquals(1, notifications.get());
    assertTrue(fixture.scheduler.scheduled.get(0).isCancelled());
    // 已标记停止：再次触发不再 renew（也不再通知）。
    beat.run();
    assertEquals(1, notifications.get());
  }

  private static Instant leaseUntil(Fixture fixture) {
    return fixture.store.transaction(
        tx -> tx.findWork(fixture.claimed.target()).orElseThrow().leaseUntil());
  }

  /** 最小可控 fixture：一个 THREAD work claim（heartbeat 与 target 类型无关）。 */
  private static final class Fixture {
    final InMemoryHarnessStore store = new InMemoryHarnessStore();
    final FakeScheduler scheduler = new FakeScheduler();
    final WorkHeartbeat heartbeat;
    final ClaimedWork claimed;

    Fixture() {
      long threadId =
          store.transaction(
              tx -> {
                long sessionId = tx.nextId();
                long rootEntryId = tx.nextId();
                long threadIdValue = tx.nextId();
                tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
                tx.insertEntry(
                    new Entry(
                        rootEntryId,
                        sessionId,
                        null,
                        new RootPayload(
                            new BranchSettings(
                                null,
                                "agent",
                                new ModelSelection("provider", "model", "v1"),
                                "low",
                                List.of())),
                        NOW));
                tx.insertThread(new ThreadState(threadIdValue, rootEntryId, false, 1, 0, NOW, NOW));
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadIdValue), NOW);
                return threadIdValue;
              });
      // claim lease 剩余（60s - 15s = 15s）必须小于 leaseDuration（30s），beat 的 renew 才能真实成功
      // （renew 要求 lease 活跃 + 严格延展）；clock 与 claim 同一时刻，避免真实时钟导致 renew 恒失败。
      this.claimed =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, NOW, "token-" + threadId, NOW.plusSeconds(15)))
              .orElseThrow();
      this.heartbeat =
          new WorkHeartbeat(
              store, scheduler, LEASE_CONFIG, Clock.fixed(NOW, ZoneOffset.UTC), () -> {});
    }
  }

  /** 最小可控 scheduler：记录 scheduleAtFixedRate 提交的 future 与 periodic task，任务由测试手动触发。 */
  static final class FakeScheduler implements ScheduledExecutorService {
    final List<FakeScheduledFuture> scheduled = new CopyOnWriteArrayList<>();
    final List<Runnable> periodicTasks = new CopyOnWriteArrayList<>();

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      FakeScheduledFuture future = new FakeScheduledFuture();
      scheduled.add(future);
      periodicTasks.add(command);
      return future;
    }

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
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public Future<?> submit(Runnable task) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException("not used by heartbeat tests");
    }
  }

  static final class FakeScheduledFuture implements ScheduledFuture<Object> {
    final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return !cancelled.getAndSet(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean isDone() {
      return cancelled.get();
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return null;
    }
  }
}
