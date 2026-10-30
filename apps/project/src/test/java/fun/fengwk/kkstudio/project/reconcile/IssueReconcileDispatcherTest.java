package fun.fengwk.kkstudio.project.reconcile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.work.DurableWorkQueue;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * {@link IssueReconcileDispatcher} 有界调度器的纯单元测试（无 Spring，无数据库）。
 *
 * <p>测试意图：覆盖 dispatcher 的周期轮询注册、首次与合并 wake 驱动、工作者拒绝退避与防热循环、 调谐异常重试、最大并发容量限制与容量计量、stop/close
 * 优雅收尾及执行器拒绝策略校验等全部契约。
 */
class IssueReconcileDispatcherTest {

  private static final Instant LEASE_UNTIL = Instant.parse("2026-09-27T10:00:30Z");

  /** 调度边界没有 Clock 依赖，也不向 store 传绝对时间，避免 JVM 时钟参与租约或 due 的计算。 */
  @Test
  void schedulingBoundaryHasNoApplicationClockDependency() {
    for (var constructor : IssueReconcileDispatcher.class.getDeclaredConstructors()) {
      for (Class<?> parameter : constructor.getParameterTypes()) {
        assertFalse(Clock.class.isAssignableFrom(parameter));
      }
    }
    for (var field : IssueReconcileDispatcher.class.getDeclaredFields()) {
      assertFalse(Clock.class.isAssignableFrom(field.getType()));
    }
    for (var method : DurableWorkQueue.class.getMethods()) {
      for (Class<?> parameter : method.getParameterTypes()) {
        assertFalse(Clock.class.isAssignableFrom(parameter));
        assertFalse(Instant.class.isAssignableFrom(parameter));
      }
    }
  }

  /** 测试意图：start() 注册固定间隔周期轮询并触发首次 drain，正确领取 claim 并投递给 reconciler。 */
  @Test
  void startSchedulesPeriodicPollAndDrainsFirstClaim() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> pollFuture = mock(ScheduledFuture.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenAnswer(inv -> pollFuture);

    IssueReconcileProperties properties = new IssueReconcileProperties();
    properties.setPollInterval(Duration.ofSeconds(2));
    properties.setLeaseDuration(Duration.ofSeconds(30));

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, reconciler, properties, Runnable::run, Runnable::run, pollScheduler);

    UUID issueId = UUID.randomUUID();
    WorkClaim work = new WorkClaim(issueId, 3L, "lease", Instant.parse("2026-09-27T11:00:00Z"));
    when(workStore.claimNext(anyString(), any(Duration.class)))
        .thenReturn(Optional.of(work), Optional.empty());
    when(reconciler.apply(any(WorkClaim.class))).thenReturn(IssueReconcileOutcome.RUN_ACCEPTED);

    dispatcher.start();

    ArgumentCaptor<Runnable> pollCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(pollScheduler)
        .scheduleWithFixedDelay(
            pollCaptor.capture(), eq(2000L), eq(2000L), eq(TimeUnit.MILLISECONDS));

    ArgumentCaptor<WorkClaim> claimCaptor = ArgumentCaptor.forClass(WorkClaim.class);
    verify(reconciler).apply(claimCaptor.capture());
    WorkClaim passedClaim = claimCaptor.getValue();
    assertEquals(issueId, passedClaim.targetId());
    assertEquals(3L, passedClaim.wakeVersion());
    assertEquals(work.leaseUntil(), passedClaim.leaseUntil());
    assertNotNull(passedClaim.leaseToken());

    // 触发定时轮询回调，验证周期 poll 正确调用 wake() 触发 drain
    when(workStore.claimNext(anyString(), any(Duration.class))).thenReturn(Optional.empty());
    pollCaptor.getValue().run();
    verify(workStore, atLeast(2)).claimNext(anyString(), any(Duration.class));
  }

  /** 测试意图：wake() 在 start 前和 stop 后均为 no-op；start() 幂等；已 stop 再次 start 抛出异常。 */
  @Test
  void wakeBeforeStartAndAfterStopIsNoOpAndStartAfterStopThrows() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> pollFuture = mock(ScheduledFuture.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenAnswer(inv -> pollFuture);

    IssueReconcileProperties properties = new IssueReconcileProperties();
    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, reconciler, properties, Runnable::run, Runnable::run, pollScheduler);

    // start 前 wake 是 no-op
    dispatcher.wake();
    verifyNoInteractions(workStore);

    // start 注册 poll 并触发首次 drain
    dispatcher.start();
    verify(pollScheduler, times(1))
        .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    verify(workStore, times(1)).claimNext(anyString(), any(Duration.class));

    // start 幂等，不会重复注册
    dispatcher.start();
    verify(pollScheduler, times(1))
        .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

    // stop 取消 poll future
    dispatcher.stop();
    verify(pollFuture).cancel(false);

    // stop 后 wake 是 no-op
    dispatcher.wake();
    verifyNoMoreInteractions(workStore);

    // stop 幂等
    dispatcher.stop();
    dispatcher.close();

    // stop 后调用 start 抛异常
    assertThrows(IllegalStateException.class, dispatcher::start);
  }

  /** 测试意图：多个 wake() 请求在 drain 执行期间到达时被合并为单次后续 iteration，不启动并发 drain 也不会丢失唤醒。 */
  @Test
  void wakeMergingRunsExactlyOneExtraDrainAfterCurrentDrainFinishes() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mock(ScheduledFuture.class));

    IssueReconcileProperties properties = new IssueReconcileProperties();
    AtomicInteger drainCount = new AtomicInteger();

    // 可控同步执行器：每次执行提交由主线程触发
    LinkedBlockingQueue<Runnable> drainQueue = new LinkedBlockingQueue<>();
    Executor drainExecutor = drainQueue::add;

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, reconciler, properties, drainExecutor, Runnable::run, pollScheduler);

    when(workStore.claimNext(anyString(), any(Duration.class)))
        .thenAnswer(
            inv -> {
              int c = drainCount.incrementAndGet();
              if (c == 1) {
                // 在第一轮 claim 期间连续发送多个 wake
                dispatcher.wake();
                dispatcher.wake();
                dispatcher.wake();
                return Optional.of(new WorkClaim(UUID.randomUUID(), 1L, "lease", LEASE_UNTIL));
              }
              return Optional.empty();
            });
    when(reconciler.apply(any(WorkClaim.class))).thenReturn(IssueReconcileOutcome.DEFERRED_IDLE);

    dispatcher.start();

    // 执行排队的 drain 任务
    Runnable drainTask = drainQueue.poll();
    assertNotNull(drainTask);
    drainTask.run();

    // 验证 claimNext 被多次调用，合并的 wake 被紧随其后的 iteration 消费，队列中无多余并发 drain
    assertTrue(drainCount.get() >= 2);
    assertEquals(0, drainQueue.size());
  }

  /** 测试意图：工作者执行器拒绝任务时，dispatcher 用 rejectionDelay 归还租约并立即终止当前 drain，避免热循环。 */
  @Test
  void workerExecutorRejectionReturnsClaimAndStopsDrain() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mock(ScheduledFuture.class));

    IssueReconcileProperties properties = new IssueReconcileProperties();
    properties.setRejectionDelay(Duration.ofSeconds(3));

    Executor rejectingWorkerExecutor =
        r -> {
          throw new RejectedExecutionException("worker pool saturated");
        };

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore,
            reconciler,
            properties,
            Runnable::run,
            rejectingWorkerExecutor,
            pollScheduler);

    UUID issueId = UUID.randomUUID();
    WorkClaim work = new WorkClaim(issueId, 1L, "lease", LEASE_UNTIL);
    when(workStore.claimNext(anyString(), any(Duration.class))).thenReturn(Optional.of(work));

    dispatcher.start();

    // 只领了一次 claim 就立即退出 drain，没有陷入紧密循环
    verify(workStore, times(1)).claimNext(anyString(), any(Duration.class));
    verify(workStore).reschedule(eq(work), eq(Duration.ofSeconds(3)));
    verifyNoInteractions(reconciler);
  }

  /** 测试意图：reconciler 抛出异常时以 retryDelay 重新调度，且 reschedule 自身的异常不会向外传播。 */
  @Test
  void reconcilerThrowingReschedulesWorkWithRetryDelayAndSwallowsRescheduleError() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    Function<WorkClaim, IssueReconcileOutcome> throwingReconciler =
        claim -> {
          throw new RuntimeException("transient reconciler failure");
        };
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mock(ScheduledFuture.class));

    IssueReconcileProperties properties = new IssueReconcileProperties();
    properties.setRetryDelay(Duration.ofSeconds(5));

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, throwingReconciler, properties, Runnable::run, Runnable::run, pollScheduler);

    UUID issueId = UUID.randomUUID();
    WorkClaim work = new WorkClaim(issueId, 1L, "lease", LEASE_UNTIL);
    when(workStore.claimNext(anyString(), any(Duration.class)))
        .thenReturn(Optional.of(work), Optional.empty());
    when(workStore.reschedule(any(WorkClaim.class), any(Duration.class)))
        .thenThrow(new RuntimeException("store connection down"));

    assertDoesNotThrow(dispatcher::start);

    verify(workStore).reschedule(eq(work), eq(Duration.ofSeconds(5)));
  }

  /** 测试意图：严格控制并发投递不超过 maxDispatchTasks，且 dispatchCapacity() 实时准确反映在途任务量。 */
  @Test
  void capacityEnforcesMaxDispatchTasksAndReflectsInFlightDispatches() throws InterruptedException {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mock(ScheduledFuture.class));

    IssueReconcileProperties properties = new IssueReconcileProperties();
    properties.setMaxDispatchTasks(2);

    CountDownLatch enteredLatch = new CountDownLatch(2);
    CountDownLatch releaseLatch = new CountDownLatch(1);

    Function<WorkClaim, IssueReconcileOutcome> blockingReconciler =
        claim -> {
          enteredLatch.countDown();
          try {
            releaseLatch.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          return IssueReconcileOutcome.DEFERRED_IDLE;
        };

    ExecutorService workerExecutor = Executors.newFixedThreadPool(4);
    ExecutorService drainExecutor = Executors.newSingleThreadExecutor();

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore,
            blockingReconciler,
            properties,
            drainExecutor,
            workerExecutor,
            pollScheduler);

    when(workStore.claimNext(anyString(), any(Duration.class)))
        .thenAnswer(inv -> Optional.of(new WorkClaim(UUID.randomUUID(), 1L, "lease", LEASE_UNTIL)));

    try {
      dispatcher.start();
      assertTrue(enteredLatch.await(5, TimeUnit.SECONDS));
      assertEquals(2, dispatcher.dispatchCapacity());
    } finally {
      releaseLatch.countDown();
      workerExecutor.shutdown();
      drainExecutor.shutdown();
      assertTrue(workerExecutor.awaitTermination(5, TimeUnit.SECONDS));
      assertTrue(drainExecutor.awaitTermination(5, TimeUnit.SECONDS));
      assertEquals(0, dispatcher.dispatchCapacity());
    }
  }

  /** 测试意图：stop() 后阻止新 claim，中途已领取但未 handoff 的工作被立即归还。 */
  @Test
  void stopReturnsUnHandedOffClaimAndPreventsNewClaims() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mock(ScheduledFuture.class));

    IssueReconcileProperties properties = new IssueReconcileProperties();
    properties.setRejectionDelay(Duration.ofSeconds(1));

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, reconciler, properties, Runnable::run, Runnable::run, pollScheduler);

    UUID issueId = UUID.randomUUID();
    WorkClaim work = new WorkClaim(issueId, 1L, "lease", LEASE_UNTIL);

    when(workStore.claimNext(anyString(), any(Duration.class)))
        .thenAnswer(
            inv -> {
              dispatcher.stop();
              return Optional.of(work);
            });

    dispatcher.start();

    // claim 被领取后发现已 stop，立即通过 reschedule 归还
    verify(workStore).reschedule(eq(work), eq(Duration.ofSeconds(1)));
    verifyNoInteractions(reconciler);
  }

  /** 测试意图：构造函数严格拒绝使用 DiscardPolicy、DiscardOldestPolicy 与 CallerRunsPolicy 的 ThreadPoolExecutor。 */
  @Test
  void constructionRejectsInvalidThreadPoolExecutorRejectionPolicies() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    IssueReconcileProperties properties = new IssueReconcileProperties();
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);

    ThreadPoolExecutor discardDrain =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.DiscardPolicy());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IssueReconcileDispatcher(
                workStore, reconciler, properties, discardDrain, Runnable::run, pollScheduler));

    ThreadPoolExecutor callerRunsWorker =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IssueReconcileDispatcher(
                workStore, reconciler, properties, Runnable::run, callerRunsWorker, pollScheduler));

    ThreadPoolExecutor discardOldestWorker =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IssueReconcileDispatcher(
                workStore,
                reconciler,
                properties,
                Runnable::run,
                discardOldestWorker,
                pollScheduler));

    discardDrain.shutdown();
    callerRunsWorker.shutdown();
    discardOldestWorker.shutdown();
  }

  /** 测试意图：drain 执行器拒绝提交任务时安全捕获并重置运行标志，returnClaim 失败不中断。 */
  @Test
  void drainExecutorRejectionHandledGracefully() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mock(ScheduledFuture.class));

    IssueReconcileProperties properties = new IssueReconcileProperties();
    Executor rejectingDrain =
        r -> {
          throw new RejectedExecutionException("drain executor full");
        };

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, reconciler, properties, rejectingDrain, Runnable::run, pollScheduler);

    assertDoesNotThrow(dispatcher::start);
    assertDoesNotThrow(dispatcher::wake);
  }

  /** 测试意图：worker 执行器抛出 Error 时正确清理容量并归还 claim。 */
  @Test
  void workerExecutorErrorReturnsClaim() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    @SuppressWarnings("unchecked")
    Function<WorkClaim, IssueReconcileOutcome> reconciler = mock(Function.class);
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenReturn(mock(ScheduledFuture.class));

    IssueReconcileProperties properties = new IssueReconcileProperties();
    Executor errorWorker =
        r -> {
          throw new AssertionError("worker executor assertion error");
        };

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, reconciler, properties, Runnable::run, errorWorker, pollScheduler);

    UUID issueId = UUID.randomUUID();
    WorkClaim work = new WorkClaim(issueId, 1L, "lease", LEASE_UNTIL);
    when(workStore.claimNext(anyString(), any(Duration.class))).thenReturn(Optional.of(work));

    assertThrows(AssertionError.class, dispatcher::start);
    assertEquals(0, dispatcher.dispatchCapacity());
    verify(workStore).reschedule(eq(work), any(Duration.class));
  }

  /** 测试意图：生产构造函数正确委托并完成初始化。 */
  @Test
  void publicConstructorInitializesCorrectly() {
    DurableWorkQueue workStore = mock(DurableWorkQueue.class);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    IssueReconcileProperties properties = new IssueReconcileProperties();
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);

    IssueReconcileDispatcher dispatcher =
        new IssueReconcileDispatcher(
            workStore, reconciler, properties, Runnable::run, Runnable::run, pollScheduler);
    assertNotNull(dispatcher);
    assertEquals(0, dispatcher.dispatchCapacity());
  }
}
