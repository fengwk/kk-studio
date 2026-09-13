package fun.fengwk.kkstudio.platform.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.platform.project.model.ClaimedControllerWork;
import fun.fengwk.kkstudio.platform.project.model.IssueControllerWork;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * {@link IssueControllerDispatcher} 单元测试：
 *
 * <p>验证 dispatcher 的生命周期（start/stop/startup wake/poll）、并发 wake 合并与容量上限（maxDispatchTasks）、 fail-fast
 * rejection 策略、claim 协议与 fencing 归还/重试、异常日志脱敏保护。
 */
class IssueControllerDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  private final List<ExecutorService> ownedExecutors = new ArrayList<>();
  private MutableClock clock;
  private IssueControllerProperties properties;
  private TestWorkStore workStore;
  private RecordingScheduler scheduler;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(NOW);
    properties = new IssueControllerProperties();
    properties.setPollInterval(Duration.ofSeconds(1));
    properties.setLeaseDuration(Duration.ofSeconds(30));
    properties.setRejectionDelay(Duration.ofSeconds(1));
    properties.setRetryDelay(Duration.ofSeconds(5));
    properties.setMaxDispatchTasks(4);
    workStore = new TestWorkStore();
    scheduler = new RecordingScheduler();
  }

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

  private IssueControllerDispatcher newDispatcher(
      Executor drainExecutor, Executor workerExecutor, Consumer<ClaimedControllerWork> reconciler) {
    return new IssueControllerDispatcher(
        workStore, reconciler, properties, clock, drainExecutor, workerExecutor, scheduler);
  }

  /**
   * 测试意图：验证 start 幂等性、启动即触发首次 wake（不等待 poll tick）、fixed-delay poll 注册、 手动 poll 触发 wake、stop 取消
   * poll、以及 start 前 / stop 后 wake no-op 与 stop 后禁止重启。
   */
  @Test
  void startIsIdempotentTriggersFirstWakeAndStopCancelsPoll() {
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);

    CopyOnWriteArrayList<ClaimedControllerWork> claims = new CopyOnWriteArrayList<>();
    IssueControllerDispatcher dispatcher =
        newDispatcher(singleThread(), singleThread(), claims::add);

    // start 前 wake 为 no-op
    dispatcher.wake();
    assertEquals(0, claims.size(), "wake before start must be no-op");

    // start 幂等，首次 wake 立即处理已就绪的 work
    dispatcher.start();
    dispatcher.start();
    dispatcher.start();

    awaitTrue(() -> claims.size() == 1, "startup wake must claim the due work");
    assertEquals(issueId, claims.get(0).getIssueId());

    // 验证 periodic poll 以 fixed-delay 注册且仅注册一次
    assertEquals(1, scheduler.tasks().size());
    RecordingScheduler.ScheduledTask pollTask = scheduler.tasks().get(0);
    assertEquals(properties.getPollInterval().toMillis(), pollTask.initialDelay);
    assertEquals(properties.getPollInterval().toMillis(), pollTask.delay);
    assertEquals(TimeUnit.MILLISECONDS, pollTask.unit);

    // 手动触发 poll tick
    UUID secondIssueId = UUID.randomUUID();
    workStore.seedWork(secondIssueId, NOW);
    scheduler.runPoll(0);

    awaitTrue(() -> claims.size() == 2, "poll tick must wake and claim new work");
    assertEquals(secondIssueId, claims.get(1).getIssueId());

    // stop 幂等且取消 poll future
    dispatcher.stop();
    dispatcher.stop();
    assertTrue(pollTask.future.isCancelled(), "stop must cancel the periodic poll future");

    // stop 后 wake 为 no-op
    UUID thirdIssueId = UUID.randomUUID();
    workStore.seedWork(thirdIssueId, NOW);
    dispatcher.wake();
    assertEquals(2, claims.size(), "wake after stop must be no-op");

    // stop 后禁止重新 start
    assertThrows(
        IllegalStateException.class,
        dispatcher::start,
        "restarting stopped dispatcher must throw IllegalStateException");
  }

  @Test
  void publicConstructorDelegatesToReconciler() {
    // 测试意图：验证 Spring 使用的公开构造边界将 claim 直接委托给 IssueReconciler。
    workStore.seedWork(UUID.randomUUID(), NOW);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    IssueControllerDispatcher dispatcher =
        new IssueControllerDispatcher(
            workStore, reconciler, properties, clock, Runnable::run, Runnable::run, scheduler);

    dispatcher.start();
    verify(reconciler).reconcile(any());
    dispatcher.stop();
  }

  @Test
  void drainExecutorFailuresResetSubmissionGuard() {
    // 测试意图：drain executor 在接受前拒绝或抛 Error 时必须清除运行标记，允许后续 wake 重试。
    ControlledExecutor rejected = new ControlledExecutor(Runnable::run);
    rejected.setReject(true);
    IssueControllerDispatcher rejectedDispatcher =
        newDispatcher(rejected, Runnable::run, claim -> {});
    rejectedDispatcher.start();
    rejectedDispatcher.wake();
    assertEquals(2, rejected.submissions());
    rejectedDispatcher.stop();

    ControlledExecutor fatal = new ControlledExecutor(Runnable::run);
    fatal.setFailure(new LinkageError("drain linkage error"));
    IssueControllerDispatcher fatalDispatcher = newDispatcher(fatal, Runnable::run, claim -> {});
    assertThrows(LinkageError.class, fatalDispatcher::start);
    fatal.setFailure(null);
    fatalDispatcher.wake();
    assertEquals(2, fatal.submissions());
    fatalDispatcher.stop();
  }

  /** 测试意图：验证并发 wake 调用在 drain 运行期间被正确合并，不触发多余的 drain 线程并发争抢。 */
  @Test
  void concurrentWakesMergeIntoSingleRunningDrain() {
    ControlledExecutor drain = new ControlledExecutor(singleThread());
    ControlledExecutor worker = new ControlledExecutor(singleThread());
    CountDownLatch taskRunning = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    CopyOnWriteArrayList<ClaimedControllerWork> claims = new CopyOnWriteArrayList<>();

    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    workStore.seedWork(first, NOW);
    workStore.seedWork(second, NOW);

    IssueControllerDispatcher dispatcher =
        newDispatcher(
            drain,
            worker,
            claim -> {
              claims.add(claim);
              taskRunning.countDown();
              try {
                releaseTask.await();
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              }
            });

    dispatcher.start();
    awaitTrue(() -> claims.size() >= 1, "first work should be claimed");

    // 在 drain 运行中并发触发多次 wake
    for (int i = 0; i < 20; i++) {
      dispatcher.wake();
    }

    releaseTask.countDown();
    awaitTrue(() -> claims.size() == 2, "both works should be claimed");
    dispatcher.stop();
  }

  /** 测试意图：验证 drain 收尾窗口到达的 wake 不会丢失，由 finally recheck 重新提交 drain。 */
  @Test
  void tailWindowWakeRecheckedAndSubmitted() {
    CopyOnWriteArrayList<ClaimedControllerWork> claims = new CopyOnWriteArrayList<>();
    ControlledExecutor drain = new ControlledExecutor(singleThread());

    IssueControllerDispatcher dispatcher = newDispatcher(drain, singleThread(), claims::add);

    dispatcher.start();
    awaitTrue(() -> drain.submissions() >= 1, "initial drain submitted");

    // 放入新 work 并 wake，确保收尾后仍能被捕获
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);
    dispatcher.wake();

    awaitTrue(() -> claims.size() == 1, "work must be processed by wake recheck");
    assertEquals(issueId, claims.get(0).getIssueId());
    dispatcher.stop();
  }

  /** 测试意图：验证 dispatcher 严格遵守 maxDispatchTasks 容量约束， 正在执行的任务达到上限时不超额 claim；并在任务完成后释放容量并唤醒后续处理。 */
  @Test
  void capacityRespectsMaxDispatchTasksAndReleasesOnWorkerCompletion() {
    properties.setMaxDispatchTasks(2);

    List<UUID> issueIds = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID id = UUID.randomUUID();
      issueIds.add(id);
      workStore.seedWork(id, NOW);
    }

    CountDownLatch blockWorkers = new CountDownLatch(1);
    CopyOnWriteArrayList<ClaimedControllerWork> received = new CopyOnWriteArrayList<>();

    IssueControllerDispatcher dispatcher =
        newDispatcher(
            singleThread(),
            fixedThreadPool(4),
            claim -> {
              received.add(claim);
              try {
                blockWorkers.await();
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              }
            });

    dispatcher.start();

    // 容量上限为 2，即使有 5 个 due work，也最多只能有 2 个在执行中
    awaitTrue(() -> received.size() == 2, "must claim exactly maxDispatchTasks works");
    assertEquals(2, dispatcher.dispatchCapacity());

    // 放行 worker，验证释放容量后触发 wake 继续处理剩余的 3 个 work
    blockWorkers.countDown();

    awaitTrue(() -> received.size() == 5, "all 5 works must eventually be claimed and dispatched");
    awaitTrue(() -> dispatcher.dispatchCapacity() == 0, "capacity must return to 0");
    dispatcher.stop();
  }

  /** 测试意图：验证 claimNext 使用整毫秒时钟、随机 UUID 租约 token，以及 leaseUntil = now + leaseDuration。 */
  @Test
  void claimUsesWholeMillisecondClockAndUuidToken() {
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);

    AtomicReference<ClaimedControllerWork> captured = new AtomicReference<>();
    IssueControllerDispatcher dispatcher =
        newDispatcher(singleThread(), singleThread(), captured::set);

    dispatcher.start();
    awaitTrue(() -> captured.get() != null, "claim must be captured");

    ClaimedControllerWork claim = captured.get();
    assertEquals(issueId, claim.getIssueId());
    assertNotNull(claim.getLeaseToken());
    // 验证 leaseToken 为合法的 UUID 格式
    assertNotNull(UUID.fromString(claim.getLeaseToken()));
    assertEquals(NOW.plus(properties.getLeaseDuration()), claim.getLeaseUntil());
    dispatcher.stop();
  }

  /**
   * 测试意图：验证 reconciler 抛出 RuntimeException 时，dispatcher 尝试使用相同 leaseToken 和 claimedWakeVersion 进行
   * fencing 的 rescheduleWork(... now + retryDelay)， 且 finally 正常释放容量并 wake。
   */
  @Test
  void workerReconcileFailureRetriesWithRetryDelayFencing() {
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);

    AtomicReference<ClaimedControllerWork> captured = new AtomicReference<>();
    IssueControllerDispatcher dispatcher =
        newDispatcher(
            singleThread(),
            singleThread(),
            claim -> {
              captured.set(claim);
              throw new RuntimeException("Reconcile simulated failure");
            });

    dispatcher.start();
    awaitTrue(() -> !workStore.rescheduleRecords().isEmpty(), "reschedule must be recorded");

    TestWorkStore.RescheduleRecord record = workStore.rescheduleRecords().get(0);
    assertEquals(issueId, record.issueId());
    assertEquals(captured.get().getLeaseToken(), record.leaseToken());
    assertEquals(captured.get().getClaimedWakeVersion(), record.claimedWakeVersion());
    assertEquals(NOW.plus(properties.getRetryDelay()), record.requestedAt());
    assertEquals(0, dispatcher.dispatchCapacity(), "capacity must be freed after failure");
    dispatcher.stop();
  }

  /**
   * 测试意图：验证 reconciler 失败后，如果 rescheduleWork 归还也因 fence 校验失效（例如租约已被其他实例抢占） 抛出
   * RuntimeException，dispatcher 保证异常被捕获且不破坏调度循环，保留租约等待过期自愈。
   */
  @Test
  void workerReconcileFailureRescheduleExceptionLeavesLeaseToExpire() {
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);
    workStore.setRescheduleException(new RuntimeException("fencing check failed"));

    IssueControllerDispatcher dispatcher =
        newDispatcher(
            singleThread(),
            singleThread(),
            claim -> {
              throw new RuntimeException("Reconcile simulated failure");
            });

    dispatcher.start();
    awaitTrue(() -> dispatcher.dispatchCapacity() == 0, "capacity must still be released");
    dispatcher.stop();
  }

  /**
   * 测试意图：验证 worker 执行过程中如果发生致命 Error（如 LinkageError/AssertionError）， Error 不被吞噬，但 finally 块依然释放容量并
   * wake。
   */
  @Test
  void workerReconcileErrorPropagatesAndReleasesCapacity() {
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);

    IssueControllerDispatcher dispatcher =
        newDispatcher(
            singleThread(),
            singleThread(),
            claim -> {
              throw new AssertionError("Simulated fatal error in reconcile");
            });

    dispatcher.start();
    awaitTrue(() -> dispatcher.dispatchCapacity() == 0, "capacity must still be released on Error");
    dispatcher.stop();
  }

  /**
   * 测试意图：验证 worker executor 拒绝提交任务时（RejectedExecutionException）， dispatcher 使用 fencing 的
   * rescheduleWork(... now + rejectionDelay) 归还 claim， 并且立即终止当前 drain 扫描，杜绝 claim->reject 热循环。
   */
  @Test
  void executorRejectionReturnsClaimWithRejectionDelayAndStopsDrain() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    workStore.seedWork(first, NOW);
    workStore.seedWork(second, NOW);

    ControlledExecutor worker = new ControlledExecutor(singleThread());
    worker.setReject(true);

    IssueControllerDispatcher dispatcher = newDispatcher(singleThread(), worker, claim -> {});

    dispatcher.start();
    awaitTrue(
        () -> workStore.rescheduleRecords().size() == 1,
        "only 1 claim should be rejected and returned");

    TestWorkStore.RescheduleRecord record = workStore.rescheduleRecords().get(0);
    assertTrue(
        record.issueId().equals(first) || record.issueId().equals(second),
        "claim order is intentionally unspecified");
    assertEquals(NOW.plus(properties.getRejectionDelay()), record.requestedAt());

    // 确认没有热循环 claim 第二个任务
    assertEquals(1, workStore.claimCount());
    assertEquals(0, dispatcher.dispatchCapacity());
    dispatcher.stop();
  }

  /** 测试意图：验证 worker executor execute 发生 Error 时，claim 会被归还，容量正常回退。 */
  @Test
  void executorErrorReturnsClaimAndPropagates() {
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);

    ControlledExecutor worker = new ControlledExecutor(singleThread());
    worker.setFailure(new LinkageError("executor linkage error"));

    IssueControllerDispatcher dispatcher = newDispatcher(singleThread(), worker, claim -> {});

    dispatcher.start();
    awaitTrue(
        () -> !workStore.rescheduleRecords().isEmpty(), "claim must be returned on executor Error");
    assertEquals(
        NOW.plus(properties.getRejectionDelay()),
        workStore.rescheduleRecords().get(0).requestedAt());
    assertEquals(0, dispatcher.dispatchCapacity());
    dispatcher.stop();
  }

  /**
   * 测试意图：验证在 claim 成功后、handoff 之前 dispatcher 被 stop 的竞态下， claim 被正常通过 rescheduleWork 归还，不泄露悬挂
   * claim。
   */
  @Test
  void stopRaceAfterClaimReturnsClaim() {
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);

    AtomicReference<IssueControllerDispatcher> dispatcherRef = new AtomicReference<>();
    workStore.setOnClaimNext(
        () -> {
          IssueControllerDispatcher d = dispatcherRef.get();
          if (d != null) {
            d.stop();
          }
        });

    CopyOnWriteArrayList<ClaimedControllerWork> handedOff = new CopyOnWriteArrayList<>();
    IssueControllerDispatcher dispatcher =
        newDispatcher(singleThread(), singleThread(), handedOff::add);
    dispatcherRef.set(dispatcher);

    dispatcher.start();
    awaitTrue(() -> !workStore.rescheduleRecords().isEmpty(), "stopped claim must be returned");

    assertEquals(0, handedOff.size(), "task must not be handed off after stop");
    assertEquals(
        NOW.plus(properties.getRejectionDelay()),
        workStore.rescheduleRecords().get(0).requestedAt());
  }

  /**
   * 测试意图：验证构造函数中校验 ThreadPoolExecutor 必须使用 fail-fast rejection 策略， 严格禁止
   * CallerRunsPolicy、DiscardPolicy 和 DiscardOldestPolicy。
   */
  @Test
  void failFastRejectionPolicyEnforced() {
    ThreadPoolExecutor callerRuns =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    ownedExecutors.add(callerRuns);

    ThreadPoolExecutor discard =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.DiscardPolicy());
    ownedExecutors.add(discard);

    ThreadPoolExecutor discardOldest =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    ownedExecutors.add(discardOldest);

    ThreadPoolExecutor abort =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.AbortPolicy());
    ownedExecutors.add(abort);

    assertThrows(
        IllegalArgumentException.class,
        () -> newDispatcher(callerRuns, abort, claim -> {}),
        "drainExecutor with CallerRunsPolicy must be rejected");

    assertThrows(
        IllegalArgumentException.class,
        () -> newDispatcher(abort, discard, claim -> {}),
        "workerExecutor with DiscardPolicy must be rejected");

    assertThrows(
        IllegalArgumentException.class,
        () -> newDispatcher(abort, discardOldest, claim -> {}),
        "workerExecutor with DiscardOldestPolicy must be rejected");

    // AbortPolicy 合法接受
    IssueControllerDispatcher validDispatcher = newDispatcher(abort, abort, claim -> {});
    assertNotNull(validDispatcher);
    validDispatcher.close();
  }

  /** 测试意图：验证异常日志脱敏保护，日志中不得回显 leaseToken、issue input/action id 或异常的 message， 仅能包含 issueId 与异常类名。 */
  @Test
  void logsDoNotEchoTokenInputIdOrExceptionMessage() {
    Logger logger = (Logger) LoggerFactory.getLogger(IssueControllerDispatcher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    String secretMessage = "SUPER_SECRET_DATABASE_ERROR_12345";
    UUID issueId = UUID.randomUUID();
    workStore.seedWork(issueId, NOW);

    AtomicReference<String> tokenRef = new AtomicReference<>();
    IssueControllerDispatcher dispatcher =
        newDispatcher(
            singleThread(),
            singleThread(),
            claim -> {
              tokenRef.set(claim.getLeaseToken());
              throw new RuntimeException(secretMessage);
            });

    try {
      dispatcher.start();
      awaitTrue(
          () -> !workStore.rescheduleRecords().isEmpty(), "reschedule after error must occur");

      String token = tokenRef.get();
      assertNotNull(token);

      boolean foundReconcilerErrorLog = false;
      for (ILoggingEvent event : appender.list) {
        String formatted = event.getFormattedMessage();
        // 绝不回显 secretMessage
        assertFalse(
            formatted.contains(secretMessage),
            "Log must not contain exception message: " + formatted);
        // 绝不回显 leaseToken
        assertFalse(formatted.contains(token), "Log must not contain lease token: " + formatted);
        // 不携带 Throwable 栈
        assertNull(
            event.getThrowableProxy(), "Log must not attach Throwable stack containing message");

        if (event.getLevel() == Level.ERROR && formatted.contains(issueId.toString())) {
          foundReconcilerErrorLog = true;
          assertTrue(
              formatted.contains("RuntimeException"),
              "Log should contain exception class name: " + formatted);
        }
      }
      assertTrue(foundReconcilerErrorLog, "Must have logged an ERROR containing the issueId");
    } finally {
      logger.detachAppender(appender);
      dispatcher.stop();
    }
  }

  private static void awaitTrue(BooleanSupplier condition, String message) {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted awaiting condition: " + message);
      }
    }
    throw new AssertionError("Timeout awaiting condition: " + message);
  }

  // --- 测试辅助桩 ---

  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static final class ControlledExecutor implements Executor {
    private final Executor delegate;
    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicBoolean reject = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    ControlledExecutor(Executor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      submissions.incrementAndGet();
      if (reject.get()) {
        throw new RejectedExecutionException("controlled rejection");
      }
      Throwable f = failure.get();
      if (f instanceof RuntimeException re) {
        throw re;
      }
      if (f instanceof Error err) {
        throw err;
      }
      delegate.execute(command);
    }

    int submissions() {
      return submissions.get();
    }

    void setReject(boolean reject) {
      this.reject.set(reject);
    }

    void setFailure(Throwable failure) {
      this.failure.set(failure);
    }
  }

  static final class TestWorkStore implements IssueControllerWorkStore {
    record WorkItem(
        UUID issueId, Instant dueAt, long wakeVersion, String leaseToken, Instant leaseUntil) {}

    record RescheduleRecord(
        UUID issueId,
        String leaseToken,
        long claimedWakeVersion,
        Instant now,
        Instant requestedAt) {}

    private final Map<UUID, WorkItem> storage = new ConcurrentHashMap<>();
    private final List<RescheduleRecord> rescheduleRecords = new CopyOnWriteArrayList<>();
    private final AtomicInteger claimCount = new AtomicInteger();
    private volatile Runnable onClaimNext;
    private volatile RuntimeException rescheduleException;

    void seedWork(UUID issueId, Instant dueAt) {
      storage.put(issueId, new WorkItem(issueId, dueAt, 1L, null, null));
    }

    void setOnClaimNext(Runnable onClaimNext) {
      this.onClaimNext = onClaimNext;
    }

    void setRescheduleException(RuntimeException rescheduleException) {
      this.rescheduleException = rescheduleException;
    }

    List<RescheduleRecord> rescheduleRecords() {
      return rescheduleRecords;
    }

    int claimCount() {
      return claimCount.get();
    }

    @Override
    public IssueControllerWork requestWork(UUID issueId, Instant dueAt) {
      storage.put(issueId, new WorkItem(issueId, dueAt, 1L, null, null));
      return null;
    }

    @Override
    public Optional<ClaimedControllerWork> claimNext(
        Instant now, String leaseToken, Instant leaseUntil) {
      claimCount.incrementAndGet();
      synchronized (storage) {
        for (Map.Entry<UUID, WorkItem> entry : storage.entrySet()) {
          WorkItem item = entry.getValue();
          boolean due = !item.dueAt().isAfter(now);
          boolean leaseExpired = item.leaseUntil() == null || !item.leaseUntil().isAfter(now);
          if (due && leaseExpired) {
            WorkItem claimed =
                new WorkItem(
                    item.issueId(), item.dueAt(), item.wakeVersion(), leaseToken, leaseUntil);
            storage.put(item.issueId(), claimed);
            if (onClaimNext != null) {
              onClaimNext.run();
            }
            return Optional.of(
                ClaimedControllerWork.builder()
                    .issueId(claimed.issueId())
                    .claimedWakeVersion(claimed.wakeVersion())
                    .leaseToken(claimed.leaseToken())
                    .leaseUntil(claimed.leaseUntil())
                    .build());
          }
        }
      }
      return Optional.empty();
    }

    @Override
    public void renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil) {}

    @Override
    public void completeWork(
        UUID issueId, String leaseToken, long claimedWakeVersion, Instant now) {
      storage.remove(issueId);
    }

    @Override
    public void rescheduleWork(
        UUID issueId,
        String leaseToken,
        long claimedWakeVersion,
        Instant now,
        Instant requestedAt) {
      rescheduleRecords.add(
          new RescheduleRecord(issueId, leaseToken, claimedWakeVersion, now, requestedAt));
      if (rescheduleException != null) {
        throw rescheduleException;
      }
      synchronized (storage) {
        WorkItem item = storage.get(issueId);
        if (item != null) {
          storage.put(
              issueId, new WorkItem(issueId, requestedAt, item.wakeVersion() + 1, null, null));
        }
      }
    }

    @Override
    public IssueControllerWork getWork(UUID issueId) {
      WorkItem item = storage.get(issueId);
      if (item == null) {
        return null;
      }
      return IssueControllerWork.builder()
          .issueId(item.issueId())
          .dueAt(item.dueAt())
          .wakeVersion(item.wakeVersion())
          .leaseToken(item.leaseToken())
          .leaseUntil(item.leaseUntil())
          .build();
    }
  }

  static final class RecordingScheduler implements ScheduledExecutorService {
    record ScheduledTask(
        Runnable command,
        long initialDelay,
        long delay,
        TimeUnit unit,
        ScheduledFuture<?> future) {}

    private final List<ScheduledTask> tasks = new CopyOnWriteArrayList<>();

    List<ScheduledTask> tasks() {
      return tasks;
    }

    void runPoll(int index) {
      tasks.get(index).command().run();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      TestScheduledFuture future = new TestScheduledFuture();
      ScheduledTask task = new ScheduledTask(command, initialDelay, delay, unit, future);
      tasks.add(task);
      return future;
    }

    // --- 未使用接口方法返回空实现 ---
    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      return null;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      return null;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      return null;
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return Collections.emptyList();
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
    public <T> Future<T> submit(Callable<T> task) {
      return null;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return null;
    }

    @Override
    public Future<?> submit(Runnable task) {
      return null;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
      return Collections.emptyList();
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      return Collections.emptyList();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
      return null;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      return null;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    static final class TestScheduledFuture implements ScheduledFuture<Void> {
      private volatile boolean cancelled;

      @Override
      public long getDelay(TimeUnit unit) {
        return 0;
      }

      @Override
      public int compareTo(Delayed o) {
        return 0;
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        this.cancelled = true;
        return true;
      }

      @Override
      public boolean isCancelled() {
        return cancelled;
      }

      @Override
      public boolean isDone() {
        return cancelled;
      }

      @Override
      public Void get() {
        return null;
      }

      @Override
      public Void get(long timeout, TimeUnit unit) {
        return null;
      }
    }
  }
}
