package fun.fengwk.kkstudio.harness.runtime.spring.dispatch;

import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.MODEL_LEASE;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.THREAD_LEASE;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.TOOL_LEASE;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.awaitTrue;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.clock;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.config;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.seedThread;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.seedTool;
import static fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.ModelSeed;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.MutableClock;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.RecordingScheduler;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.ThreadSeed;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.DispatcherTestSupport.ToolSeed;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** handoff 语义：按类型路由、claim 字段、Processor 失败语义与真实 Processor wiring。 */
class HarnessWorkDispatcherHandoffTest {

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
      int maxDispatchTasks,
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler) {
    return new HarnessWorkDispatcher(
        store,
        config(maxDispatchTasks),
        clock,
        drain,
        worker,
        poll,
        threadHandler,
        modelHandler,
        toolHandler);
  }

  @Test
  void routesClaimsToTheMatchingTypeConsumer() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    ToolSeed toolSeed = seedTool(store);
    CopyOnWriteArrayList<ClaimedWork> threadClaims = new CopyOnWriteArrayList<>();
    CopyOnWriteArrayList<ClaimedWork> modelClaims = new CopyOnWriteArrayList<>();
    CopyOnWriteArrayList<ClaimedWork> toolClaims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            3,
            threadClaims::add,
            modelClaims::add,
            toolClaims::add);

    dispatcher.start();
    awaitTrue(() -> threadClaims.size() == 1 && modelClaims.size() == 1 && toolClaims.size() == 1);
    assertEquals(
        new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId()), threadClaims.get(0).target());
    assertEquals(
        new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()),
        modelClaims.get(0).target());
    assertEquals(
        new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId()),
        toolClaims.get(0).target());
  }

  @Test
  void claimsUsePerTypeLeasesFreshTokensAndWholeMillisecondTime() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    seedThread(store);
    seedModel(store);
    seedTool(store);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            3,
            claims::add,
            claims::add,
            claims::add);

    dispatcher.start();
    awaitTrue(() -> claims.size() == 3);
    for (ClaimedWork claim : claims) {
      Duration expectedLease =
          switch (claim.target().type()) {
            case THREAD -> THREAD_LEASE;
            case MODEL -> MODEL_LEASE;
            case TOOL -> TOOL_LEASE;
          };
      assertEquals(NOW.plus(expectedLease), claim.leaseUntil());
      assertTrue(isUuidToken(claim.leaseToken()));
    }

    // 同一目标再次 claim 必须拿到新 token。
    ClaimedWork firstThreadClaim = claims.get(0);
    store.transaction(
        tx -> {
          tx.rescheduleWork(firstThreadClaim, NOW, NOW);
          return null;
        });
    dispatcher.wake();
    awaitTrue(() -> claims.size() == 4);
    ClaimedWork secondThreadClaim = claims.get(3);
    assertEquals(WorkTargetType.THREAD, secondThreadClaim.target().type());
    assertNotEquals(firstThreadClaim.leaseToken(), secondThreadClaim.leaseToken());
  }

  @Test
  void theDispatcherClockIsRoundedToMilliseconds() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed seed = seedThread(store);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    // 亚毫秒源时钟：不包装会直接违反 store 的毫秒精度约束。
    Instant subMillisecondNow = NOW.plusNanos(123_456_789);
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            Clock.fixed(subMillisecondNow, ZoneOffset.UTC),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            1,
            claims::add,
            claims::add,
            claims::add);

    dispatcher.start();
    awaitTrue(() -> claims.size() == 1);
    Instant truncatedNow = NOW.plusMillis(123);
    assertEquals(truncatedNow.plus(THREAD_LEASE), claims.get(0).leaseUntil());
    assertEquals(0, claims.get(0).leaseUntil().getNano() % 1_000_000);
    assertEquals(seed.threadId(), claims.get(0).target().id());
  }

  @Test
  void normalProcessorReturnDoesNotTriggerAnyDispatcherDurableMutation() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            1,
            claims::add,
            claims::add,
            claims::add);

    dispatcher.start();
    awaitTrue(() -> claims.size() >= 1);
    ClaimedWork claim = claims.get(0);
    Work work = work(store, new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId()));
    // dispatcher 不 complete / reschedule / delete：lease 原样保留。
    assertEquals(claim.leaseToken(), work.leaseToken());
    assertEquals(claim.leaseUntil(), work.leaseUntil());
    assertEquals(NOW, work.availableAt());
    assertEquals(1L, work.wakeVersion());

    // slot 释放后下一个 due 目标被 claim。
    awaitTrue(() -> claims.size() == 2);
    assertEquals(
        new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()),
        claims.get(1).target());
  }

  @Test
  void runtimeExceptionFromTheProcessorKeepsTheLeaseButReleasesTheSlot() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    Consumer<ClaimedWork> failingHandler =
        claim -> {
          claims.add(claim);
          throw new IllegalStateException("processor boom");
        };
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            1,
            failingHandler,
            claims::add,
            claims::add);

    dispatcher.start();
    awaitTrue(() -> claims.size() >= 1);
    Work work = work(store, new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId()));
    // 保留 lease 待过期恢复：不 complete / reschedule / delete。
    assertEquals(claims.get(0).leaseToken(), work.leaseToken());
    assertEquals(claims.get(0).leaseUntil(), work.leaseUntil());
    assertEquals(NOW, work.availableAt());

    // 本地 capacity 已释放：下一个 due 目标仍可被 claim。
    awaitTrue(() -> claims.size() == 2);
    assertEquals(
        new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()),
        claims.get(1).target());
  }

  @Test
  void errorFromTheProcessorIsNotSwallowedButTheSlotIsStillReleased() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    ExecutorService worker =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setUncaughtExceptionHandler((t, error) -> uncaught.set(error));
              return thread;
            });
    ownedExecutors.add(worker);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    AssertionError boom = new AssertionError("processor boom");
    Consumer<ClaimedWork> failingHandler =
        claim -> {
          claims.add(claim);
          throw boom;
        };
    HarnessWorkDispatcher dispatcher =
        newDispatcher(
            store,
            clock(),
            singleThread(),
            worker,
            new RecordingScheduler(),
            1,
            failingHandler,
            claims::add,
            claims::add);

    dispatcher.start();
    awaitTrue(() -> uncaught.get() != null);
    assertSame(boom, uncaught.get());

    // finally 仍然执行：capacity 释放，下一个 due 目标可被 claim。
    awaitTrue(() -> claims.size() == 2);
    assertEquals(
        new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()),
        claims.get(1).target());
  }

  @Test
  void routesClaimsToTheRealProcessors() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    ModelSeed modelSeed = seedModel(store);
    ToolSeed toolSeed = seedTool(store);
    MutableClock clock = clock();
    FakeModelGateway modelGateway = new FakeModelGateway();
    FakeToolGateway toolGateway = new FakeToolGateway();
    RealtimeEventSink sink = event -> {};
    ProcessorLeaseConfig leaseConfig =
        new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
    InvocationRetryPolicy noRetry =
        new InvocationRetryPolicy(
            0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    ScheduledExecutorService processorScheduler = Executors.newScheduledThreadPool(1);
    ownedExecutors.add(processorScheduler);
    ThreadProcessor threadProcessor =
        new ThreadProcessor(
            store,
            (threadId, path, yoloEnabled) -> {
              throw new AssertionError("resolver must not be called for a quiescent thread");
            },
            new ThreadProcessorConfig(leaseConfig, 10, Duration.ofSeconds(1)),
            clock,
            processorScheduler);
    ModelProcessor modelProcessor =
        new ModelProcessor(
            store,
            modelGateway,
            sink,
            new ModelProcessorConfig(
                leaseConfig, Duration.ofSeconds(5), noRetry, Duration.ofSeconds(5)),
            clock,
            processorScheduler);
    ToolProcessor toolProcessor =
        new ToolProcessor(
            store,
            toolGateway,
            sink,
            new ToolProcessorConfig(
                leaseConfig, noRetry, Duration.ofSeconds(7), Duration.ofSeconds(9)),
            clock,
            processorScheduler);
    HarnessWorkDispatcher dispatcher =
        new HarnessWorkDispatcher(
            store,
            config(3),
            clock,
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            threadProcessor,
            modelProcessor,
            toolProcessor);

    dispatcher.start();
    awaitTrue(
        () ->
            work(store, new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId())) == null
                && modelGateway.started.size() == 1
                && toolGateway.preflightCalls.get() == 1
                && toolGateway.started.size() == 1);
    awaitTrue(
        () ->
            store
                        .transaction(
                            tx ->
                                tx.findModelInvocation(modelSeed.modelInvocationId()).orElseThrow())
                        .status()
                    == ModelInvocationStatus.READY
                && store
                        .transaction(
                            tx -> tx.findToolInvocation(toolSeed.toolInvocationId()).orElseThrow())
                        .status()
                    == ToolInvocationStatus.READY);

    // THREAD claim 由 ThreadProcessor 处理：quiescent 路径 completeWork 删除了行。
    // MODEL claim 由 ModelProcessor 处理：gateway 收到正确的 invocation id。
    assertEquals(modelSeed.modelInvocationId(), modelGateway.started.get(0));
    // TOOL claim 由 ToolProcessor 处理：gateway 收到正确的 invocation id。
    assertEquals(toolSeed.toolInvocationId(), toolGateway.started.get(0));
    // 两个 invocation 都被 Busy bounce 回 READY（未开始执行）。
    ModelInvocation model =
        store
            .transaction(tx -> tx.findModelInvocation(modelSeed.modelInvocationId()))
            .orElseThrow();
    assertEquals(ModelInvocationStatus.READY, model.status());
    ToolInvocation tool =
        store.transaction(tx -> tx.findToolInvocation(toolSeed.toolInvocationId())).orElseThrow();
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(0, model.attempt());
    assertEquals(0, tool.attempt());
  }

  @Test
  void constructorsFailFastOnNullArguments() {
    HarnessWorkDispatcherConfig config = config(1);
    Consumer<ClaimedWork> handler = claim -> {};
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                null,
                config,
                clock(),
                singleThread(),
                singleThread(),
                new RecordingScheduler(),
                handler,
                handler,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                null,
                clock(),
                singleThread(),
                singleThread(),
                new RecordingScheduler(),
                handler,
                handler,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                null,
                singleThread(),
                singleThread(),
                new RecordingScheduler(),
                handler,
                handler,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                clock(),
                null,
                singleThread(),
                new RecordingScheduler(),
                handler,
                handler,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                clock(),
                singleThread(),
                null,
                new RecordingScheduler(),
                handler,
                handler,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                clock(),
                singleThread(),
                singleThread(),
                null,
                handler,
                handler,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                clock(),
                singleThread(),
                singleThread(),
                new RecordingScheduler(),
                null,
                handler,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                clock(),
                singleThread(),
                singleThread(),
                new RecordingScheduler(),
                handler,
                null,
                handler));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                clock(),
                singleThread(),
                singleThread(),
                new RecordingScheduler(),
                handler,
                handler,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config,
                clock(),
                singleThread(),
                singleThread(),
                new RecordingScheduler(),
                (ThreadProcessor) null,
                null,
                null));
  }

  @Test
  void constructorsRejectKnownNonFailFastThreadPoolRejectionPolicies() {
    ThreadPoolExecutor callerRuns =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.CallerRunsPolicy());
    ThreadPoolExecutor discard =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardPolicy());
    ThreadPoolExecutor discardOldest =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    ownedExecutors.add(callerRuns);
    ownedExecutors.add(discard);
    ownedExecutors.add(discardOldest);
    Consumer<ClaimedWork> handler = claim -> {};

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config(1),
                clock(),
                callerRuns,
                Runnable::run,
                new RecordingScheduler(),
                handler,
                handler,
                handler));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config(1),
                clock(),
                discard,
                Runnable::run,
                new RecordingScheduler(),
                handler,
                handler,
                handler));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessWorkDispatcher(
                new InMemoryHarnessStore(),
                config(1),
                clock(),
                Runnable::run,
                discardOldest,
                new RecordingScheduler(),
                handler,
                handler,
                handler));
  }

  private static boolean isUuidToken(String token) {
    return token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  private static final class FakeModelGateway implements ModelGateway {

    private final List<Long> started = new CopyOnWriteArrayList<>();

    @Override
    public StartResult start(Execution execution, Listener listener) {
      started.add(execution.invocationId());
      return new ModelGateway.Busy(Duration.ofSeconds(5));
    }
  }

  private static final class FakeToolGateway implements ToolGateway {

    private final List<Long> started = new CopyOnWriteArrayList<>();
    private final AtomicInteger preflightCalls = new AtomicInteger();

    @Override
    public PreflightResult preflight(ToolInvocationRequest request, boolean yoloEnabled) {
      preflightCalls.incrementAndGet();
      return new ToolGateway.Allow();
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      started.add(execution.invocationId());
      return new ToolGateway.Busy(Duration.ofSeconds(9));
    }
  }
}
