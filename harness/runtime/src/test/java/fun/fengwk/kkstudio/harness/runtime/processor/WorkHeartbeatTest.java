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
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
   * beat 与 stop 同锁互斥：在途 renew 事务（已获取 renewalLock 并进入 store 事务）执行期间， stop() 必须阻塞等待该事务完成释放锁；事务释放后
   * stop() 顺利返回； stop() 返回后手动触发 tick 也绝不再开始任何 renewal 事务。
   */
  @Test
  void stopWaitsForInFlightBeatAndNoRenewAfterStopReturns() throws Exception {
    Fixture fixture = new Fixture();
    CountDownLatch renewalInsideTx = new CountDownLatch(1);
    CountDownLatch releaseTx = new CountDownLatch(1);

    HarnessStore blockingStore =
        new HarnessStore() {
          @Override
          public <T> T transaction(Function<Transaction, T> callback) {
            return fixture.store.transaction(
                tx -> {
                  renewalInsideTx.countDown();
                  try {
                    releaseTx.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                  }
                  return callback.apply(tx);
                });
          }
        };

    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            blockingStore,
            fixture.scheduler,
            Runnable::run,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> {});
    assertTrue(heartbeat.start(fixture.claimed));
    Runnable tick = fixture.scheduler.periodicTasks.get(0);

    // 1. 在单独线程中启动 tick（异步执行 renew）
    Thread renewThread = new Thread(tick);
    renewThread.start();

    CountDownLatch stopStarted = new CountDownLatch(1);
    CountDownLatch stopReturned = new CountDownLatch(1);
    Thread stopThread =
        new Thread(
            () -> {
              stopStarted.countDown();
              heartbeat.stop();
              stopReturned.countDown();
            });

    try {
      // 2. 严格等待 renew 已获取 renewalLock 并已进入 blockingStore.transaction
      assertTrue(
          renewalInsideTx.await(5, TimeUnit.SECONDS),
          "renew must have entered blockingStore transaction before stop");

      // 3. 此时发起 stop()，并等待线程确定阻塞在 renewalLock。
      stopThread.start();
      assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
      awaitBlocked(stopThread);
      assertEquals(1L, stopReturned.getCount());
    } finally {
      // 4. 放行 renew 事务，并安全收敛线程
      releaseTx.countDown();
      renewThread.join(5000);
      stopThread.join(5000);
    }

    // 5. 验证 stop() 此时已顺利返回，scheduler future 已取消
    assertTrue(stopReturned.await(5, TimeUnit.SECONDS));
    assertTrue(fixture.scheduler.scheduled.get(0).isCancelled());

    // 6. stop 返回后：periodic task 即使再次触发，也绝不调用 store.transaction 续租
    Instant before = leaseUntil(fixture);
    tick.run();
    assertEquals(before, leaseUntil(fixture), "no renew after stop returns");
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
            Runnable::run,
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

  /** 隔离性：一个 claim 的续租被阻塞（如等数据库行锁）时，定时调度线程绝不被占用或阻塞， 另一个 claim 的心跳定时任务仍可正常触发并执行续租。 */
  @Test
  void blockedRenewalDoesNotBlockTimerAndAllowsOtherHeartbeatDispatch() throws Exception {
    Fixture fixtureA = new Fixture();
    Fixture fixtureB = new Fixture();

    CountDownLatch blockA = new CountDownLatch(1);
    CountDownLatch workerAStarted = new CountDownLatch(1);
    CountDownLatch renewedB = new CountDownLatch(1);
    try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
      WorkHeartbeat heartbeatA =
          new WorkHeartbeat(
              fixtureA.store,
              fixtureA.scheduler,
              command ->
                  worker.execute(
                      () -> {
                        workerAStarted.countDown();
                        try {
                          blockA.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException failure) {
                          Thread.currentThread().interrupt();
                        }
                        command.run();
                      }),
              LEASE_CONFIG,
              Clock.fixed(NOW, ZoneOffset.UTC),
              () -> {});
      assertTrue(heartbeatA.start(fixtureA.claimed));
      WorkHeartbeat heartbeatB =
          new WorkHeartbeat(
              fixtureB.store,
              fixtureB.scheduler,
              command ->
                  worker.execute(
                      () -> {
                        command.run();
                        renewedB.countDown();
                      }),
              LEASE_CONFIG,
              Clock.fixed(NOW, ZoneOffset.UTC),
              () -> {});
      assertTrue(heartbeatB.start(fixtureB.claimed));

      Runnable tickA = fixtureA.scheduler.periodicTasks.get(0);
      Runnable tickB = fixtureB.scheduler.periodicTasks.get(0);

      try {
        // A 的 worker 被阻塞后，timer 仍可立即继续分派 B。
        tickA.run();
        assertTrue(workerAStarted.await(5, TimeUnit.SECONDS), "workerA must receive dispatch");

        Instant beforeB = leaseUntil(fixtureB);
        tickB.run();
        assertTrue(
            renewedB.await(5, TimeUnit.SECONDS),
            "heartbeat B must complete renewal without blocking on A");
        Instant afterB = leaseUntil(fixtureB);
        assertTrue(
            afterB.isAfter(beforeB), "heartbeat B must renew even while heartbeat A is blocked");
      } finally {
        blockA.countDown();
        heartbeatA.stop();
        heartbeatB.stop();
      }
    }
  }

  /** 合并机制：当一次续租处于在途（queued 或 running）状态时，后续重复到达的定时 tick 会被合并丢弃， 至多创建一个续租任务，绝不无界堆积。 */
  @Test
  void repeatedTicksWhileRenewalIsActiveAreCoalesced() throws Exception {
    Fixture fixture = new Fixture();
    List<Runnable> queuedTasks = new CopyOnWriteArrayList<>();

    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            fixture.store,
            fixture.scheduler,
            queuedTasks::add,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> {});
    assertTrue(heartbeat.start(fixture.claimed));

    Runnable tick = fixture.scheduler.periodicTasks.get(0);

    // 第一项仍在 worker 队列中时，后续 tick 被合并。
    tick.run();
    assertEquals(1, queuedTasks.size());
    tick.run();
    tick.run();
    tick.run();
    assertEquals(1, queuedTasks.size(), "active renewal must coalesce overlapping ticks");

    queuedTasks.get(0).run();
    tick.run();
    assertEquals(2, queuedTasks.size(), "a completed renewal permits the next dispatch");
    queuedTasks.get(1).run();
    heartbeat.stop();
  }

  /** scheduler 拒绝启动：视为所有权无法维系，start 返回 false 并恰好一次通知 onLostOwnership。 */
  @Test
  void schedulerRejectionMarksStoppedAndNotifiesLostOwnershipOnce() throws Exception {
    Fixture fixture = new Fixture();
    fixture.scheduler.reject.set(true);
    AtomicInteger notifications = new AtomicInteger();
    CountDownLatch lostLatch = new CountDownLatch(1);

    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            fixture.store,
            fixture.scheduler,
            Runnable::run,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> {
              notifications.incrementAndGet();
              lostLatch.countDown();
            });
    assertFalse(heartbeat.start(fixture.claimed));
    assertTrue(lostLatch.await(5, TimeUnit.SECONDS));
    assertEquals(1, notifications.get());

    // 再次 start 仍返回 false，且不再重复通知
    assertFalse(heartbeat.start(fixture.claimed));
    assertEquals(1, notifications.get());
  }

  /** worker 拒绝分派：视为所有权无法维系，恰好一次通知 onLostOwnership 并取消后续 tick。 */
  @Test
  void workerRejectionMarksStoppedCancelsFutureAndNotifiesLostOwnershipOnce() throws Exception {
    Fixture fixture = new Fixture();
    AtomicInteger notifications = new AtomicInteger();
    CountDownLatch lostLatch = new CountDownLatch(1);
    Executor rejectingWorker =
        task -> {
          throw new RejectedExecutionException("worker saturated");
        };

    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            fixture.store,
            fixture.scheduler,
            rejectingWorker,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> {
              notifications.incrementAndGet();
              lostLatch.countDown();
            });
    assertTrue(heartbeat.start(fixture.claimed));

    Runnable tick = fixture.scheduler.periodicTasks.get(0);
    tick.run();

    assertTrue(
        lostLatch.await(5, TimeUnit.SECONDS),
        "lost ownership must be notified on worker rejection");
    assertEquals(1, notifications.get());
    assertTrue(
        fixture.scheduler.scheduled.get(0).isCancelled(), "future must be cancelled on rejection");

    // 再次触发 tick 为 no-op，不再重复通知
    tick.run();
    assertEquals(1, notifications.get());
  }

  /** 停止不变量：在 renewal 已分派入队但尚未开始执行期间调用 stop()， 待该任务稍后执行时绝不得再次 renew lease，亦不触发失联通知。 */
  @Test
  void stopDuringQueuedRenewalPreventsLeaseRenewal() {
    Fixture fixture = new Fixture();
    List<Runnable> queuedTasks = new CopyOnWriteArrayList<>();
    Executor queuedWorker = queuedTasks::add;
    AtomicInteger lostNotifications = new AtomicInteger();

    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            fixture.store,
            fixture.scheduler,
            queuedWorker,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            lostNotifications::incrementAndGet);
    assertTrue(heartbeat.start(fixture.claimed));

    Runnable tick = fixture.scheduler.periodicTasks.get(0);
    tick.run();
    assertEquals(1, queuedTasks.size(), "tick must enqueue renewal task to worker");

    Instant before = leaseUntil(fixture);

    // 在排队任务执行前调用 stop()
    heartbeat.stop();
    assertTrue(fixture.scheduler.scheduled.get(0).isCancelled());

    // 随后执行排队的 renewal 任务
    queuedTasks.get(0).run();

    // 验证 lease 未被更新且无 lost ownership 通知
    assertEquals(before, leaseUntil(fixture), "queued renewal must not renew lease after stop()");
    assertEquals(0, lostNotifications.get(), "normal stop does not notify lost ownership");
  }

  /** Executor 的非拒绝型运行时异常也必须 fail closed，不能让 periodic task 静默终止。 */
  @Test
  void workerRuntimeFailureMarksStoppedAndNotifiesLostOwnership() {
    Fixture fixture = new Fixture();
    AtomicInteger lostNotifications = new AtomicInteger();
    AtomicInteger dispatches = new AtomicInteger();
    Executor failingWorker =
        task -> {
          if (dispatches.getAndIncrement() == 0) {
            throw new IllegalStateException("worker unavailable");
          }
          task.run();
        };

    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            fixture.store,
            fixture.scheduler,
            failingWorker,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            lostNotifications::incrementAndGet);
    assertTrue(heartbeat.start(fixture.claimed));

    Runnable tick = fixture.scheduler.periodicTasks.get(0);
    tick.run();

    assertEquals(1, lostNotifications.get());
    assertEquals(2, dispatches.get());
    assertTrue(fixture.scheduler.scheduled.get(0).isCancelled());
  }

  /**
   * 竞态消除：当 tick 分派已进入 worker.execute 期间并发触发正常 stop()， worker 随后抛出拒绝异常时必须静默抑制，断言失联通知发生次数严格为 0
   * 且无线程泄漏。
   */
  @Test
  void concurrentStopDuringWorkerRejectionLeavesNoSpuriousLostOwnership() throws Exception {
    Fixture fixture = new Fixture();
    AtomicInteger lostNotifications = new AtomicInteger();
    CountDownLatch executeEntered = new CountDownLatch(1);
    CountDownLatch stopInvoked = new CountDownLatch(1);

    CountDownLatch allowRejection = new CountDownLatch(1);
    Executor controlledWorker =
        task -> {
          executeEntered.countDown();
          try {
            allowRejection.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          throw new RejectedExecutionException("worker saturated during concurrent stop");
        };

    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            fixture.store,
            fixture.scheduler,
            controlledWorker,
            LEASE_CONFIG,
            Clock.fixed(NOW, ZoneOffset.UTC),
            lostNotifications::incrementAndGet);
    assertTrue(heartbeat.start(fixture.claimed));

    Runnable tick = fixture.scheduler.periodicTasks.get(0);
    Thread tickThread = new Thread(tick);
    Thread stopThread =
        new Thread(
            () -> {
              stopInvoked.countDown();
              heartbeat.stop();
            });

    try {
      // 1. tickThread 启动并在 stateLock 内调用 controlledWorker.execute
      tickThread.start();
      assertTrue(executeEntered.await(5, TimeUnit.SECONDS));

      // stop() 已设置正常停止标记，并确定阻塞在 tick 持有的 stateLock。
      stopThread.start();
      assertTrue(stopInvoked.await(5, TimeUnit.SECONDS));
      awaitBlocked(stopThread);
      allowRejection.countDown();
    } finally {
      allowRejection.countDown();
      tickThread.join(5000);
      stopThread.join(5000);
    }

    assertEquals(
        0,
        lostNotifications.get(),
        "normal stop racing worker rejection must not trigger lost ownership");
    assertFalse(tickThread.isAlive());
    assertFalse(stopThread.isAlive());
  }

  private static void awaitBlocked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive()
        && thread.getState() != Thread.State.BLOCKED
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(Thread.State.BLOCKED, thread.getState(), "thread must block on heartbeat monitor");
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
      UUID threadId =
          store.transaction(
              tx -> {
                UUID sessionId = tx.nextId();
                UUID rootEntryId = tx.nextId();
                UUID threadIdValue = tx.nextId();
                tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
                tx.insertEntry(
                    new Entry(
                        rootEntryId,
                        sessionId,
                        null,
                        new RootPayload(
                            new BranchSettings(
                                "agent", new ModelSelection("provider", "model", "v1"))),
                        NOW));
                tx.insertThread(
                    new ThreadState(
                        threadIdValue,
                        sessionId,
                        rootEntryId,
                        ThreadProcessorTestSupport.CREATION_REQUEST_HASH,
                        "main",
                        false,
                        1,
                        0,
                        NOW,
                        NOW));
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
              store,
              scheduler,
              Runnable::run,
              LEASE_CONFIG,
              Clock.fixed(NOW, ZoneOffset.UTC),
              () -> {});
    }
  }

  /** 最小可控 scheduler：记录 scheduleAtFixedRate 提交的 future 与 periodic task，任务由测试手动触发。 */
  static final class FakeScheduler implements ScheduledExecutorService {
    final List<FakeScheduledFuture> scheduled = new CopyOnWriteArrayList<>();
    final List<Runnable> periodicTasks = new CopyOnWriteArrayList<>();
    final AtomicBoolean reject = new AtomicBoolean();

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      if (reject.get()) {
        throw new RejectedExecutionException("scheduler saturated");
      }
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
