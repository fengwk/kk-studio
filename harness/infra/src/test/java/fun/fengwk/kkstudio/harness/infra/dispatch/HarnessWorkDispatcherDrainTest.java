package fun.fengwk.kkstudio.harness.infra.dispatch;

import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.REJECTION_DELAY;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.awaitTrue;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.clock;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.config;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedThread;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedTool;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.BlockingStore;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ControlledExecutor;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ModelSeed;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.MutableClock;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.RecordingScheduler;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ThreadSeed;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ToolSeed;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** drain 语义：容量上限、round-robin 公平、wake 合并、rejection 归还与热循环防护。 */
class HarnessWorkDispatcherDrainTest {

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

  private ExecutorService fixedThreadPool(int size) {
    ExecutorService executor = Executors.newFixedThreadPool(size);
    ownedExecutors.add(executor);
    return executor;
  }

  private HarnessWorkDispatcher newDispatcher(
      HarnessStore store,
      Clock clock,
      Executor drain,
      Executor worker,
      ScheduledExecutorService poll,
      int maxDispatchTasks,
      Consumer<ClaimedWork> handler) {
    return new HarnessWorkDispatcher(
        store, config(maxDispatchTasks), clock, drain, worker, poll, handler, handler, handler);
  }

  @Test
  void concurrentWakesMergeIntoTheRunningDrainAndLoseNothing() throws InterruptedException {
    InMemoryHarnessStore inner = new InMemoryHarnessStore();
    BlockingStore store = new BlockingStore(inner);
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    ControlledExecutor drain = new ControlledExecutor(singleThread());
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    CountDownLatch releaseConsumers = new CountDownLatch(1);
    Consumer<ClaimedWork> blockingRecorder =
        claim -> {
          claims.add(claim);
          try {
            releaseConsumers.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
          }
        };
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            drain,
            fixedThreadPool(2),
            new RecordingScheduler(),
            2,
            blockingRecorder);

    store.arm();
    dispatcher.start();
    store.awaitEntered();
    // drain 线程被钉在 claim 事务上时并发 wake：只合并、不产生新 drain。
    for (int i = 0; i < 10; i++) {
      dispatcher.wake();
    }
    assertEquals(1, drain.submissions());

    store.release();
    awaitTrue(() -> claims.size() == 2);
    // 全部 wake 被单 drain 吸收：没有额外 drain 任务，两个 due work 都没有丢失。
    assertEquals(1, drain.submissions());
    assertTrue(
        work(inner, new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId())).leaseToken()
            != null);
    assertTrue(
        work(inner, new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()))
                .leaseToken()
            != null);
    releaseConsumers.countDown();
  }

  @Test
  void maxOneStillRoundRobinsAcrossTypesOverMultipleDrains() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed thread1 = seedThread(store);
    ThreadSeed thread2 = seedThread(store);
    ModelSeed model1 = seedModel(store);
    ModelSeed model2 = seedModel(store);
    ToolSeed tool1 = seedTool(store);
    ToolSeed tool2 = seedTool(store);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    Consumer<ClaimedWork> completingRecorder =
        claim -> {
          claims.add(claim);
          store.transaction(
              tx -> {
                tx.completeWork(claim, NOW);
                return null;
              });
        };
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            1,
            completingRecorder);

    dispatcher.start();
    awaitTrue(() -> claims.size() == 6);

    List<WorkTargetType> types = claims.stream().map(claim -> claim.target().type()).toList();
    assertEquals(
        List.of(
            WorkTargetType.THREAD,
            WorkTargetType.MODEL,
            WorkTargetType.TOOL,
            WorkTargetType.THREAD,
            WorkTargetType.MODEL,
            WorkTargetType.TOOL),
        types);
    // 每个目标都被消费（completeWork 删除行）。
    assertNull(work(store, new WorkTarget(WorkTargetType.THREAD, thread1.threadId())));
    assertNull(work(store, new WorkTarget(WorkTargetType.THREAD, thread2.threadId())));
    assertNull(work(store, new WorkTarget(WorkTargetType.MODEL, model1.modelInvocationId())));
    assertNull(work(store, new WorkTarget(WorkTargetType.MODEL, model2.modelInvocationId())));
    assertNull(work(store, new WorkTarget(WorkTargetType.TOOL, tool1.toolInvocationId())));
    assertNull(work(store, new WorkTarget(WorkTargetType.TOOL, tool2.toolInvocationId())));
  }

  @Test
  void capacityIsBoundedByMaxDispatchTasks() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    ToolSeed toolSeed = seedTool(store);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    CountDownLatch releaseConsumer = new CountDownLatch(1);
    Consumer<ClaimedWork> blockingRecorder =
        claim -> {
          claims.add(claim);
          try {
            releaseConsumer.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
          }
        };
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            2,
            blockingRecorder);

    dispatcher.start();
    // 单线程 worker：第一个 task 运行中，第二个排队 —— 两者都计入 maxDispatchTasks=2。
    awaitTrue(
        () ->
            work(store, new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId())).leaseToken()
                    != null
                && work(store, new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()))
                        .leaseToken()
                    != null);
    assertTrue(
        work(store, new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId())).leaseToken()
            != null);
    assertTrue(
        work(store, new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()))
                .leaseToken()
            != null);
    // 第三个目标从未被 claim（capacity 满时 drain 停止）。
    assertNull(
        work(store, new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId())).leaseToken());

    releaseConsumer.countDown();
    // 释放一个 slot 后 TOOL 被 claim。
    awaitTrue(() -> claims.size() == 3);
    assertTrue(
        work(store, new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId())).leaseToken()
            != null);
  }

  @Test
  void drainExecutorRejectionRecoversOnTheNextWake() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    ControlledExecutor drain = new ControlledExecutor(singleThread());
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store, clock(), drain, singleThread(), new RecordingScheduler(), 1, claims::add);

    drain.setFailure(new IllegalStateException("drain unavailable"));
    dispatcher.start();
    assertEquals(1, drain.submissions());
    assertTrue(claims.isEmpty());
    assertNull(work(store, new WorkTarget(WorkTargetType.THREAD, seed.threadId())).leaseToken());

    // executor 恢复后下一个 wake 正常 drain。
    drain.setFailure(null);
    dispatcher.wake();
    awaitTrue(() -> claims.size() == 1);
    assertTrue(drain.submissions() >= 2);
  }

  @Test
  void drainExecutorErrorRestoresTheSubmissionGateBeforePropagating() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    seedThread(store);
    ControlledExecutor drain = new ControlledExecutor(Runnable::run);
    AssertionError failure = new AssertionError("drain failed");
    drain.setFailure(failure);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store, clock(), drain, Runnable::run, new RecordingScheduler(), 1, claims::add);

    assertSame(failure, assertThrows(AssertionError.class, dispatcher::start));
    drain.setFailure(null);
    dispatcher.wake();
    awaitTrue(() -> claims.size() == 1);
  }

  @Test
  void workerRejectionReturnsTheOwnedClaimWithThePositiveDelay() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    ControlledExecutor worker = new ControlledExecutor(singleThread());
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store, clock(), singleThread(), worker, new RecordingScheduler(), 1, claims::add);

    worker.setBlocking(true);
    dispatcher.start();
    // claim 已提交，drain 线程被钉在 handoff 的 execute() 上。
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, seed.threadId());
    awaitTrue(() -> work(store, target).leaseToken() != null);

    worker.setReject(true);
    worker.releaseBlocking();
    awaitTrue(() -> work(store, target).leaseToken() == null);
    Work returned = work(store, target);
    assertEquals(NOW.plus(REJECTION_DELAY), returned.availableAt());
    assertEquals(1L, returned.wakeVersion());
    assertNull(returned.leaseToken());
    assertTrue(claims.isEmpty());
  }

  @Test
  void workerSubmissionRuntimeFailureReturnsTheClaimAndReleasesCapacity() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    ControlledExecutor worker = new ControlledExecutor(Runnable::run);
    worker.setFailure(new IllegalStateException("worker unavailable"));
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store, clock(), Runnable::run, worker, new RecordingScheduler(), 1, claims::add);

    dispatcher.start();
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId());
    awaitTrue(
        () -> {
          Work current = work(store, threadTarget);
          return current.availableAt().equals(NOW.plus(REJECTION_DELAY))
              && current.leaseToken() == null;
        });

    worker.setFailure(null);
    dispatcher.wake();
    awaitTrue(() -> claims.size() == 1);
    assertEquals(
        new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()),
        claims.get(0).target());
  }

  @Test
  void workerSubmissionErrorReturnsTheClaimBeforePropagating() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    ControlledExecutor worker = new ControlledExecutor(Runnable::run);
    AssertionError failure = new AssertionError("worker failed");
    worker.setFailure(failure);
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store, clock(), Runnable::run, worker, new RecordingScheduler(), 1, claim -> {});

    assertSame(failure, assertThrows(AssertionError.class, dispatcher::start));
    Work returned = work(store, new WorkTarget(WorkTargetType.THREAD, seed.threadId()));
    assertEquals(NOW.plus(REJECTION_DELAY), returned.availableAt());
    assertNull(returned.leaseToken());
  }

  @Test
  void workerRejectionWithLostOwnershipIsANoOp() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    ControlledExecutor worker = new ControlledExecutor(singleThread());
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    MutableClock mutableClock = clock();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store, mutableClock, singleThread(), worker, new RecordingScheduler(), 1, claims::add);

    worker.setBlocking(true);
    dispatcher.start();
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, seed.threadId());
    awaitTrue(() -> work(store, target).leaseToken() != null);
    Work claimed = work(store, target);

    // lease 过期后归还路径必须 lost no-op：不清 lease、不重排。
    mutableClock.set(NOW.plusSeconds(31));
    worker.setReject(true);
    worker.releaseBlocking();
    Thread.sleep(100);

    Work after = work(store, target);
    assertEquals(claimed.leaseToken(), after.leaseToken());
    assertEquals(claimed.leaseUntil(), after.leaseUntil());
    assertEquals(NOW, after.availableAt());
    assertEquals(1L, after.wakeVersion());
    assertTrue(claims.isEmpty());
  }

  @Test
  void workerRejectionEndsTheDrainWithoutAClaimRejectHotLoop() throws InterruptedException {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    Executor rejectingWorker =
        command -> {
          throw new RejectedExecutionException("worker is down");
        };
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            rejectingWorker,
            new RecordingScheduler(),
            4,
            claims::add);

    dispatcher.start();
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId());
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId());
    awaitTrue(
        () -> {
          Work current = work(store, threadTarget);
          return current.leaseToken() == null
              && current.availableAt().equals(NOW.plus(REJECTION_DELAY));
        });

    // THREAD 被归还：按正延迟重排。
    Work returned = work(store, threadTarget);
    assertEquals(NOW.plus(REJECTION_DELAY), returned.availableAt());
    assertEquals(1L, returned.wakeVersion());

    // 同一 drain 在第一次 rejection 后立即结束：MODEL 从未被 claim，也没有任何重试循环。
    Work untouched = work(store, modelTarget);
    assertNull(untouched.leaseToken());
    assertEquals(NOW, untouched.availableAt());
    assertEquals(1L, untouched.wakeVersion());
    assertTrue(claims.isEmpty());
  }

  @Test
  void nodeInstanceIdIsForwardedAndFiltersAffinityClaim() {
    UUID allowedNodeId = UUID.randomUUID();
    UUID otherNodeId = UUID.randomUUID();
    EnvironmentId env = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

    InMemoryHarnessStore store =
        new InMemoryHarnessStore(
            (nodeInstanceId, environmentId2, now) ->
                allowedNodeId.equals(nodeInstanceId) && env.equals(environmentId2));

    ToolSeed toolSeed = seedTool(store);

    CopyOnWriteArrayList<ClaimedWork> otherClaims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher otherDispatcher =
        new HarnessWorkDispatcher(
            otherNodeId,
            store,
            config(1),
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            otherClaims::add,
            otherClaims::add,
            otherClaims::add);

    otherDispatcher.start();
    // otherDispatcher should not claim the tool work because its nodeInstanceId does not match the
    // route
    assertNull(
        work(store, new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId())).leaseToken());
    assertTrue(otherClaims.isEmpty());
    otherDispatcher.stop();

    CopyOnWriteArrayList<ClaimedWork> allowedClaims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher allowedDispatcher =
        new HarnessWorkDispatcher(
            allowedNodeId,
            store,
            config(1),
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            allowedClaims::add,
            allowedClaims::add,
            allowedClaims::add);

    allowedDispatcher.start();
    awaitTrue(() -> !allowedClaims.isEmpty());
    assertEquals(1, allowedClaims.size());
    assertEquals(toolSeed.toolInvocationId(), allowedClaims.get(0).target().id());
    assertEquals(env, allowedClaims.get(0).requiredEnvironmentId());
    allowedDispatcher.stop();
  }
}
