package fun.fengwk.kkstudio.harness.infra.dispatch;

import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.awaitTrue;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.clock;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.config;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedThread;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedTool;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.MutableClock;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.RecordingScheduler;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ThreadSeed;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchAdmission;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.StreamFlushConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 「宿主拒绝新的对外执行」经真实 Processor wiring 后的端到端语义：dispatcher 照常 handoff，Processor 在 READY -&gt;
 * DISPATCHING 的持久意图事务内询问宿主，被拒绝时 invocation 事实不变、Work 按 {@code admissionDeferral} durable
 * 重排、绝不形成热循环。
 *
 * <p>测试意图：准入判定已经从 dispatcher 的 claim/handoff 预检下沉到 Processor 的持久意图边界（见 {@link
 * WorkDispatchAdmission}），因此这里用真实 Processor wiring 守住两点：拒绝仍然只延后执行而不丢弃、不改写 invocation；在途与等待态 claim
 * 依然被 handoff，暂停/收尾不会因为「宿主拒绝一切」而无法收敛。
 */
class HarnessWorkDispatcherAdmissionTest {

  private static final Duration ADMISSION_DEFERRAL = Duration.ofSeconds(3);

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

  private ScheduledExecutorService scheduler() {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    ownedExecutors.add(scheduler);
    return scheduler;
  }

  /** 记录每次询问并作答的宿主策略：默认拒绝一切新的对外执行。 */
  private static final class RecordingAdmission implements WorkDispatchAdmission {

    private final List<WorkDispatchRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean admitsNewExecution;

    RecordingAdmission(boolean admitsNewExecution) {
      this.admitsNewExecution = admitsNewExecution;
    }

    @Override
    public <T> Optional<T> executeIfAdmitted(WorkDispatchRequest request, Supplier<T> intent) {
      requests.add(request);
      return admitsNewExecution ? Optional.ofNullable(intent.get()) : Optional.empty();
    }

    void admitNewExecution() {
      this.admitsNewExecution = true;
    }
  }

  private record Wiring(
      HarnessWorkDispatcher dispatcher,
      FakeModelGateway modelGateway,
      FakeToolGateway toolGateway) {}

  private Wiring wiring(
      InMemoryHarnessStore store,
      MutableClock clock,
      WorkDispatchAdmission admission,
      int maxDispatchTasks) {
    FakeModelGateway modelGateway = new FakeModelGateway();
    FakeToolGateway toolGateway = new FakeToolGateway();
    RealtimeEventSink sink = event -> {};
    ProcessorLeaseConfig leaseConfig =
        new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
    InvocationRetryPolicy noRetry =
        new InvocationRetryPolicy(
            0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    ScheduledExecutorService processorScheduler = scheduler();
    ThreadProcessor threadProcessor =
        new ThreadProcessor(
            store,
            (threadId, path, preparation) -> {
              throw new AssertionError("resolver must not be called for a quiescent thread");
            },
            new ThreadProcessorConfig(
                leaseConfig, Duration.ofSeconds(1), () -> CompactionConfig.DEFAULT),
            clock,
            processorScheduler,
            Runnable::run);
    ModelProcessor modelProcessor =
        new ModelProcessor(
            store,
            modelGateway,
            sink,
            new ModelProcessorConfig(
                leaseConfig,
                () -> noRetry,
                Duration.ofSeconds(5),
                ADMISSION_DEFERRAL,
                StreamFlushConfig.DEFAULT,
                null),
            clock,
            processorScheduler,
            Runnable::run,
            Runnable::run,
            admission);
    ToolProcessor toolProcessor =
        new ToolProcessor(
            store,
            toolGateway,
            sink,
            new ToolProcessorConfig(
                leaseConfig,
                () -> noRetry,
                Duration.ofSeconds(7),
                Duration.ofSeconds(9),
                ADMISSION_DEFERRAL),
            clock,
            processorScheduler,
            Runnable::run,
            admission);
    HarnessWorkDispatcher dispatcher =
        new HarnessWorkDispatcher(
            store,
            config(maxDispatchTasks),
            clock,
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            threadProcessor,
            modelProcessor,
            toolProcessor);
    return new Wiring(dispatcher, modelGateway, toolGateway);
  }

  private static ModelInvocation model(HarnessStore store, UUID invocationId) {
    return store.transaction(tx -> tx.findModelInvocation(invocationId).orElseThrow());
  }

  /** Work 是否已被宿主拒绝派发后 durable 重排（lease 已归还 + 重排到 admissionDeferral 之后）。 */
  private static boolean deferred(HarnessStore store, WorkTarget target) {
    Work work = work(store, target);
    return work != null
        && work.leaseToken() == null
        && NOW.plus(ADMISSION_DEFERRAL).equals(work.availableAt());
  }

  private static ToolInvocation tool(HarnessStore store, UUID invocationId) {
    return store.transaction(tx -> tx.findToolInvocation(invocationId).orElseThrow());
  }

  @Test
  void deniedReadyModelIsDeferredDurablyWithoutGatewayAndWithoutHotLoop() throws Exception {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var modelSeed = seedModel(store);
    MutableClock clock = clock();
    RecordingAdmission admission = new RecordingAdmission(false);
    Wiring wiring = wiring(store, clock, admission, 3);
    WorkTarget target = new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId());

    wiring.dispatcher().start();
    try {
      // 拒绝只延后执行：Work durable 重排到 admissionDeferral 之后、lease 归还，invocation 事实完全不变、不调用 Provider。
      awaitTrue(() -> deferred(store, target));
      assertEquals(
          ModelInvocationStatus.READY, model(store, modelSeed.modelInvocationId()).status());
      assertTrue(wiring.modelGateway().started.isEmpty());
      assertEquals(1, admission.requests.size(), admission.requests.toString());

      // 未到重排时刻前的 wake 只会重新扫描而不能重新派发：静默窗口内不产生新的询问，也没有 Provider 调用。
      wiring.dispatcher().wake();
      Thread.sleep(200);
      assertEquals(1, admission.requests.size(), admission.requests.toString());
      assertTrue(wiring.modelGateway().started.isEmpty());
      assertEquals(NOW.plus(ADMISSION_DEFERRAL), work(store, target).availableAt());

      // 恢复放行 + 越过重排时刻后，同一 Work 被重新派发并真正启动 Provider。
      admission.admitNewExecution();
      clock.set(NOW.plus(ADMISSION_DEFERRAL));
      wiring.dispatcher().wake();
      awaitTrue(() -> wiring.modelGateway().started.size() == 1);
      assertEquals(modelSeed.modelInvocationId(), wiring.modelGateway().started.get(0));
      assertEquals(2, admission.requests.size(), admission.requests.toString());
    } finally {
      wiring.dispatcher().stop();
    }
  }

  @Test
  void deniedReadyToolIsDeferredDurablyAfterPreflightWithoutToolExecution() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var toolSeed = seedTool(store);
    MutableClock clock = clock();
    RecordingAdmission admission = new RecordingAdmission(false);
    Wiring wiring = wiring(store, clock, admission, 1);
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId());

    wiring.dispatcher().start();
    try {
      // preflight 是权限评估（只读、无副作用）而不是对外执行，允许先于宿主询问；但宿主一旦拒绝，绝不调用 Tool gateway.start，
      // 审批与派发事实也一概不变，Work 同样 durable 重排。
      awaitTrue(() -> deferred(store, target));
      assertEquals(1, wiring.toolGateway().preflightCalls.get());
      assertTrue(wiring.toolGateway().started.isEmpty());
      ToolInvocation tool = tool(store, toolSeed.toolInvocationId());
      assertEquals(ToolInvocationStatus.READY, tool.status());
      assertNull(tool.approval());
      assertEquals(1, admission.requests.size(), admission.requests.toString());
      assertEquals(NOW.plus(ADMISSION_DEFERRAL), work(store, target).availableAt());
    } finally {
      wiring.dispatcher().stop();
    }
  }

  @Test
  void inFlightClaimsAndThreadWorkAreHandedOffEvenWhenTheHostDeniesEverything() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadSeed threadSeed = seedThread(store);
    var modelSeed = seedModel(store);
    var toolSeed = seedTool(store);
    Instant started = NOW.plusSeconds(5);
    markModelRunning(store, modelSeed.modelInvocationId(), started);
    markToolRunning(store, toolSeed.toolInvocationId(), started);
    RecordingAdmission admission = new RecordingAdmission(false);
    Wiring wiring = wiring(store, clock(), admission, 3);

    wiring.dispatcher().start();
    try {
      // 在途 claim 只观察 / 收敛已可能存在的对外 handle（恢复旧 lease、取消），暂停不能让它们卡住。
      awaitTrue(
          () ->
              work(store, new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()))
                      == null
                  && work(store, new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId()))
                      == null
                  && work(store, new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId()))
                      == null);
      assertTrue(admission.requests.isEmpty(), admission.requests.toString());
      assertTrue(wiring.modelGateway().started.isEmpty());
      assertTrue(wiring.toolGateway().started.isEmpty());
    } finally {
      wiring.dispatcher().stop();
    }
  }

  @Test
  void waitingToolClaimIsHandedOffWithoutAskingTheHost() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var toolSeed = seedTool(store);
    parkToolForInput(store, toolSeed.toolInvocationId(), NOW.plusSeconds(5));
    RecordingAdmission admission = new RecordingAdmission(false);
    Wiring wiring = wiring(store, clock(), admission, 1);

    wiring.dispatcher().start();
    try {
      // 等待态 claim 只收敛 durable 事实：暂停期间回答仍能落盘，等待态 claim 也能正常完成。
      awaitTrue(
          () ->
              work(store, new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId()))
                  == null);
      assertTrue(admission.requests.isEmpty(), admission.requests.toString());
      assertEquals(
          ToolInvocationStatus.WAITING_INPUT, tool(store, toolSeed.toolInvocationId()).status());
    } finally {
      wiring.dispatcher().stop();
    }
  }

  private static void markModelRunning(
      InMemoryHarnessStore store, UUID modelInvocationId, Instant now) {
    store.transaction(
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(modelInvocationId).orElseThrow();
          // 分两步推进：Store 按已持久化状态校验跃迁，READY -> DISPATCHING -> RUNNING 各写一次。
          tx.updateModelInvocation(model.beginDispatch(now));
          tx.updateModelInvocation(model.beginDispatch(now).markRunning(now));
          return null;
        });
  }

  private static void markToolRunning(
      InMemoryHarnessStore store, UUID toolInvocationId, Instant now) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolInvocationId).orElseThrow();
          // preflight 通过后才是 READY -> DISPATCHING -> RUNNING：每一步都是 Store 认可的单个跃迁。
          tx.updateToolInvocations(List.of(tool.markApprovalNotRequired(now)));
          tx.updateToolInvocations(List.of(tool.markApprovalNotRequired(now).beginDispatch(now)));
          tx.updateToolInvocations(
              List.of(tool.markApprovalNotRequired(now).beginDispatch(now).markRunning(now)));
          return null;
        });
  }

  private static void parkToolForInput(
      InMemoryHarnessStore store, UUID toolInvocationId, Instant now) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolInvocationId).orElseThrow();
          tx.updateToolInvocations(List.of(tool.requestInput(now)));
          return null;
        });
  }

  /** 记录每次真正启动的 MODEL 调用：拒绝派发时不得被调用。 */
  private static final class FakeModelGateway implements ModelGateway {

    private final List<UUID> started = new CopyOnWriteArrayList<>();

    @Override
    public StartResult start(Execution execution, Listener listener) {
      started.add(execution.invocationId());
      return new ModelGateway.Busy(Duration.ofSeconds(5));
    }
  }

  /** 记录每次 preflight 与真正启动的 TOOL 调用：拒绝派发只允许发生 preflight。 */
  private static final class FakeToolGateway implements ToolGateway {

    private final List<UUID> started = new CopyOnWriteArrayList<>();
    private final AtomicInteger preflightCalls = new AtomicInteger();

    @Override
    public PreflightResult preflight(ToolInvocationRequest request) {
      preflightCalls.incrementAndGet();
      return new ToolGateway.Allow();
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      started.add(execution.invocationId());
      return new ToolGateway.RetryLater(Duration.ofSeconds(9));
    }
  }
}
