package fun.fengwk.kkstudio.harness.infra.dispatch;

import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.ADMISSION_DEFERRAL;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.awaitTrue;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.clock;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedThread;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.seedTool;
import static fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.MutableClock;
import fun.fengwk.kkstudio.harness.infra.dispatch.DispatcherTestSupport.RecordingScheduler;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchAdmission;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 宿主派发准入（{@link WorkDispatchAdmission}）语义：只对「可能首次对外执行」的 claim 询问宿主，拒绝只延后执行。
 *
 * <p>测试意图：产品层的暂停/阻塞必须能阻止新的模型调用与工具执行，但不能阻断任何已经开始的执行——否则暂停会让在途执行永远无法收尾。因此这里 守住三类区别：
 *
 * <ul>
 *   <li>READY 的 MODEL / TOOL claim 是「首次对外执行」，必须询问宿主，并带上所属 Thread、Session 与冻结工具绑定；被拒绝时不做 handoff、不消耗
 *       wakeVersion、lease 归还并按 {@code admissionDeferral} 重排。
 *   <li>DISPATCHING / RUNNING 的 claim 只观察或收敛已可能存在的对外 handle（在途轮询、恢复与取消），等待态与终态的 claim 只收敛 durable
 *       事实：即使宿主拒绝一切也照常 handoff，保证暂停始终能安全收敛。
 *   <li>THREAD claim 只物化历史与规划 turn：不询问宿主，也不被延后。
 * </ul>
 */
class HarnessWorkDispatcherAdmissionTest {

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
      InMemoryHarnessStore store,
      Clock clock,
      ExecutorService drain,
      ExecutorService worker,
      WorkDispatchAdmission admission,
      Consumer<ClaimedWork> handler) {
    return new HarnessWorkDispatcher(
        store,
        DispatcherTestSupport.config(4),
        clock,
        drain,
        worker,
        new RecordingScheduler(),
        handler,
        handler,
        handler,
        admission);
  }

  /** 记录每次询问并作答的宿主策略：默认拒绝一切新的对外执行。 */
  private static final class RecordingAdmission implements WorkDispatchAdmission {

    private final List<WorkDispatchRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean admitsNewExecution;

    RecordingAdmission(boolean admitsNewExecution) {
      this.admitsNewExecution = admitsNewExecution;
    }

    @Override
    public boolean admits(WorkDispatchRequest request) {
      requests.add(request);
      return admitsNewExecution;
    }

    void admitNewExecution() {
      this.admitsNewExecution = true;
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

  @Test
  void readyModelAndToolAskTheHostWithTheirOwningCoordinates() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var modelSeed = seedModel(store);
    var toolSeed = seedTool(store);
    long modelWakeVersion =
        work(store, new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()))
            .wakeVersion();
    RecordingAdmission admission = new RecordingAdmission(false);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(store, clock(), singleThread(), fixedThreadPool(2), admission, claims::add);

    dispatcher.start();
    try {
      awaitTrue(() -> admission.requests.size() >= 2);
      // 归属按线程解析：MODEL / TOOL 自身 id 都不是坐标，必须解析到其所属 Thread 与 Session。
      List<WorkDispatchRequest> requests = admission.requests.stream().distinct().toList();
      assertEquals(2, requests.size(), requests.toString());
      WorkDispatchRequest model =
          requests.stream().filter(r -> r.type() == WorkTargetType.MODEL).findFirst().orElseThrow();
      assertEquals(modelSeed.modelInvocationId(), model.invocationId());
      assertEquals(modelSeed.threadId(), model.threadId());
      assertNotNull(model.sessionId());
      assertNull(model.toolBinding());
      WorkDispatchRequest tool =
          requests.stream().filter(r -> r.type() == WorkTargetType.TOOL).findFirst().orElseThrow();
      assertEquals(toolSeed.toolInvocationId(), tool.invocationId());
      // TOOL 的坐标是它的 owning Thread（经模型调用解析），不是工具自身的 id。
      assertEquals(toolSeed.threadId(), tool.threadId());
      assertNotNull(tool.sessionId());
      // 冻结绑定随请求一起交给宿主：只读性判定来自冻结能力声明，不需要再查目录。
      assertNotNull(tool.toolBinding());
      assertEquals("bash", tool.toolBinding().descriptor().name());
      assertEquals(ToolSideEffect.READ_ONLY, tool.toolBinding().descriptor().sideEffect());
      // 拒绝即不 handoff，但事实与调度状态都保留。
      assertTrue(claims.isEmpty());
      Work modelAfter =
          work(store, new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId()));
      Work toolAfter =
          work(store, new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId()));
      assertEquals(modelWakeVersion, modelAfter.wakeVersion());
      assertNull(modelAfter.leaseToken());
      assertNull(toolAfter.leaseToken());
      assertEquals(NOW.plus(ADMISSION_DEFERRAL), modelAfter.availableAt());
      assertEquals(NOW.plus(ADMISSION_DEFERRAL), toolAfter.availableAt());
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  void deferredWorkIsDispatchedOnceAdmissionIsRestored() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var modelSeed = seedModel(store);
    var toolSeed = seedTool(store);
    MutableClock clock = clock();
    RecordingAdmission admission = new RecordingAdmission(false);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(store, clock, singleThread(), fixedThreadPool(2), admission, claims::add);

    dispatcher.start();
    try {
      awaitTrue(() -> admission.requests.size() >= 2);
      assertTrue(claims.isEmpty());
      // 恢复放行 + 时间越过 admissionDeferral 后，同一 Work 被重新派发。
      admission.admitNewExecution();
      clock.set(NOW.plus(ADMISSION_DEFERRAL));
      dispatcher.wake();
      awaitTrue(() -> claims.size() == 2);
      List<WorkTarget> dispatched = claims.stream().map(ClaimedWork::target).toList();
      assertTrue(
          dispatched.contains(new WorkTarget(WorkTargetType.MODEL, modelSeed.modelInvocationId())));
      assertTrue(
          dispatched.contains(new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId())));
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  void inFlightModelAndToolAreHandedOffEvenWhenTheHostDeniesEverything() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var modelSeed = seedModel(store);
    var toolSeed = seedTool(store);
    Instant started = NOW.plusSeconds(5);
    markModelRunning(store, modelSeed.modelInvocationId(), started);
    markToolRunning(store, toolSeed.toolInvocationId(), started);
    RecordingAdmission admission = new RecordingAdmission(false);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(store, clock(), singleThread(), fixedThreadPool(2), admission, claims::add);

    dispatcher.start();
    try {
      // 在途 claim 只观察/收敛已可能存在的对外 handle（轮询、恢复、取消）：暂停不能让它们卡住，否则永远无法安全收尾。
      awaitTrue(() -> claims.size() == 2);
      assertTrue(admission.requests.isEmpty(), admission.requests.toString());
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  void waitingAndThreadClaimsAreHandedOffWithoutAskingTheHost() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var threadSeed = seedThread(store);
    var toolSeed = seedTool(store);
    parkToolForInput(store, toolSeed.toolInvocationId(), NOW.plusSeconds(5));
    RecordingAdmission admission = new RecordingAdmission(false);
    CopyOnWriteArrayList<ClaimedWork> claims = new CopyOnWriteArrayList<>();
    HarnessWorkDispatcher dispatcher =
        newDispatcher(store, clock(), singleThread(), fixedThreadPool(2), admission, claims::add);

    dispatcher.start();
    try {
      // 等待态工具与 THREAD 都只收敛/物化 durable 事实，不产生对外执行：暂停期间回答仍能落盘，等待态 claim 也能正常完成。
      awaitTrue(() -> claims.size() == 2);
      List<WorkTarget> dispatched = claims.stream().map(ClaimedWork::target).toList();
      assertTrue(dispatched.contains(new WorkTarget(WorkTargetType.THREAD, threadSeed.threadId())));
      assertTrue(
          dispatched.contains(new WorkTarget(WorkTargetType.TOOL, toolSeed.toolInvocationId())));
      assertTrue(admission.requests.isEmpty(), admission.requests.toString());
    } finally {
      dispatcher.stop();
    }
  }
}
