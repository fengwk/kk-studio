package fun.fengwk.kkstudio.harness.infra.dispatch;

import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.POLL_INTERVAL;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.REJECTION_DELAY;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.awaitTrue;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.clock;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.config;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedThread;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.BlockingStore;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ControlledExecutor;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.MutableClock;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.RecordingScheduler;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.RecordingScheduler.ScheduledTask;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ThreadSeed;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** start / wake / stop 生命周期：幂等、首次 wake、periodic fixed-delay、stop cancel 与 stop 竞态归还。 */
class HarnessWorkDispatcherLifecycleTest {

  private final List<ExecutorService> ownedExecutors = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (ExecutorService executor : ownedExecutors) {
      executor.shutdownNow();
    }
  }

  private ExecutorService singleThread() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ownedExecutors.add(executor);
    return executor;
  }

  private HarnessWorkDispatcher newDispatcher(
      HarnessStore store,
      Clock clock,
      Executor drain,
      Executor worker,
      ScheduledExecutorService poll,
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler) {
    return new HarnessWorkDispatcher(
        store, config(), clock, drain, worker, poll, threadHandler, modelHandler, toolHandler);
  }

  @Test
  void startIsIdempotentTriggersFirstWakeAndStopCancelsThePoll() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    RecordingScheduler scheduler = new RecordingScheduler();
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            scheduler,
            claims::add,
            claims::add,
            claims::add);

    dispatcher.start();
    dispatcher.start();
    dispatcher.start();

    // 首次 wake：无需任何 poll tick 就完成了第一个 claim。
    awaitTrue(() -> claims.size() == 1);
    assertEquals(new WorkTarget(WorkTargetType.THREAD, seed.threadId()), claims.get(0).target());

    // periodic poll 以 fixed-delay 注册且只注册一次。
    assertEquals(1, scheduler.tasks().size());
    ScheduledTask task = scheduler.tasks().get(0);
    assertEquals(POLL_INTERVAL.toMillis(), task.initialDelay);
    assertEquals(POLL_INTERVAL.toMillis(), task.delay);
    assertEquals(TimeUnit.MILLISECONDS, task.unit);

    // 手动 tick 触发 poll -> wake -> drain -> claim。
    store.transaction(
        tx -> {
          tx.lockThread(seed.threadId());
          WorkTarget target = new WorkTarget(WorkTargetType.THREAD, seed.threadId());
          tx.deleteWork(target);
          tx.requestWork(target, NOW);
          return null;
        });
    scheduler.runPoll(0);
    awaitTrue(() -> claims.size() == 2);

    dispatcher.stop();
    dispatcher.stop();
    assertEquals(1, task.future.cancelCalls());
    assertTrue(task.future.isCancelled());
  }

  @Test
  void startAfterStopThrows() {
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            new InMemoryHarnessStore(),
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            claim -> {},
            claim -> {},
            claim -> {});
    dispatcher.stop();
    assertThrows(IllegalStateException.class, dispatcher::start);
  }

  @Test
  void wakeBeforeStartIsIgnoredWithoutAnyPollRegistration() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    seedThread(store);
    RecordingScheduler scheduler = new RecordingScheduler();
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            scheduler,
            claims::add,
            claims::add,
            claims::add);

    dispatcher.wake();
    Thread.sleep(100);
    assertTrue(claims.isEmpty());
    assertTrue(scheduler.tasks().isEmpty());
  }

  @Test
  void wakeAfterStopDoesNotStartAnyClaim() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            claims::add,
            claims::add,
            claims::add);

    dispatcher.stop();
    dispatcher.wake();
    Thread.sleep(100);
    assertTrue(claims.isEmpty());
    assertNull(work(store, new WorkTarget(WorkTargetType.THREAD, seed.threadId())).leaseToken());
  }

  @Test
  void periodicPollReclaimsNewlyDueWork() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    MutableClock clock = clock();
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    ScheduledExecutorService poll = Executors.newScheduledThreadPool(1);
    ownedExecutors.add(poll);
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock,
            singleThread(),
            singleThread(),
            poll,
            claims::add,
            claims::add,
            claims::add);

    // 把 Work 重排到 150ms 后 due：start 的首次 wake 不会 claim。
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, seed.threadId());
    store.transaction(
        tx -> {
          tx.lockThread(seed.threadId());
          tx.deleteWork(target);
          tx.requestWork(target, NOW.plusMillis(150));
          return null;
        });
    dispatcher.start();
    Thread.sleep(50);
    assertTrue(claims.isEmpty());

    // 推进时钟后，由 periodic poll（fixed-delay 20ms）claim。
    clock.set(NOW.plusMillis(200));
    awaitTrue(() -> claims.size() == 1);
    dispatcher.stop();
  }

  @Test
  void stopDoesNotShutdownInjectedExecutorsNorInterruptAcceptedTasks() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    ExecutorService drain = singleThread();
    ExecutorService worker = singleThread();
    CountDownLatch handlerEntered = new CountDownLatch(1);
    CountDownLatch releaseHandler = new CountDownLatch(1);
    AtomicBoolean handlerCompleted = new AtomicBoolean();
    AtomicBoolean interruptedAtEnd = new AtomicBoolean();
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    Consumer<ClaimedWork> blockingHandler =
        claim -> {
          claims.add(claim);
          handlerEntered.countDown();
          try {
            releaseHandler.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
          }
          interruptedAtEnd.set(Thread.currentThread().isInterrupted());
          handlerCompleted.set(true);
        };
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            drain,
            worker,
            new RecordingScheduler(),
            blockingHandler,
            blockingHandler,
            blockingHandler);

    dispatcher.start();
    awaitTrue(() -> handlerEntered.getCount() == 0);
    dispatcher.stop();
    releaseHandler.countDown();

    // 已接受 task 不受 stop 影响：运行到完成且未被 interrupt，claim 从未被归还。
    awaitTrue(handlerCompleted::get);
    assertFalse(interruptedAtEnd.get());
    Work work = work(store, new WorkTarget(WorkTargetType.THREAD, seed.threadId()));
    assertEquals(claims.get(0).leaseToken(), work.leaseToken());

    // 注入 executor 未被 shutdown，stop 后仍可提交并执行任务。
    AtomicBoolean drainRan = new AtomicBoolean();
    AtomicBoolean workerRan = new AtomicBoolean();
    drain.execute(() -> drainRan.set(true));
    worker.execute(() -> workerRan.set(true));
    awaitTrue(drainRan::get);
    awaitTrue(workerRan::get);
  }

  @Test
  void stopWinningBetweenClaimAndHandoffReturnsTheClaim() throws InterruptedException {
    InMemoryHarnessStore inner = new InMemoryHarnessStore();
    BlockingStore store = new BlockingStore(inner);
    ThreadSeed seed = seedThread(store);
    ControlledExecutor drain = new ControlledExecutor(singleThread());
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            drain,
            singleThread(),
            new RecordingScheduler(),
            claims::add,
            claims::add,
            claims::add);

    store.arm();
    dispatcher.start();
    store.awaitEntered();
    dispatcher.stop();
    store.release();

    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, seed.threadId());
    // 初始 Work 本来就是 leaseToken=null；只等待该条件会在 drain 真正 claim/归还前提前通过。
    // wakeVersion 与 availableAt 一起证明 ownership-fenced return transaction 已完整提交。
    awaitTrue(
        () -> {
          Work current = work(inner, target);
          return current.leaseToken() == null
              && current.wakeVersion() == 1L
              && current.availableAt().equals(NOW.plus(REJECTION_DELAY));
        });
    Work returned = work(inner, target);
    assertNotNull(returned);
    // ownership-fenced 归还：按正 executorRejectionDelay 重排并清除 lease。
    assertEquals(NOW.plus(REJECTION_DELAY), returned.availableAt());
    assertEquals(1L, returned.wakeVersion());
    assertNull(returned.leaseToken());
    assertTrue(claims.isEmpty());
    assertEquals(1, drain.submissions());
  }
}
