package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchAdmission;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ToolProcessor 宿主派发准入的物理事务边界：宿主判定与 READY -&gt; DISPATCHING 意图必须在同一个 store 事务里，外部调用必须在其后。
 *
 * <p>测试意图：门禁的强一致不来自「先问再看」，而来自「宿主行锁 + 产品判定 + Harness 状态转换同处一个物理事务，且提交之后才对外调用」。因此这里用记录 store
 * 事务边界的装饰器把三件事钉死：
 *
 * <ol>
 *   <li>请求在任何 Harness 行锁之前、且在 Processor 自己开启的 store 事务内发出（同一事务边界，不是两次独立提交）；
 *   <li>被拒绝时意图一次都没有执行，invocation 事实一概不变，Work 只按 {@code admissionDeferral} durable 重排并归还 lease；
 *   <li>被放行时授权与 {@code DISPATCHING} 在同一事务内落盘，ToolGateway 的 preflight（权限评估）与 start（对外执行）都在任何 store
 *       事务之外发生，即先提交再调用外部。
 * </ol>
 */
class ToolProcessorHostAdmissionTest {

  private static final Duration ADMISSION_DEFERRAL = Duration.ofSeconds(3);
  private static final RealtimeEventSink SINK = event -> {};

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  @AfterEach
  void stopScheduler() {
    scheduler.shutdownNow();
  }

  /** 只观察 Processor 自己发起的事务边界：装饰器包住 store，种子数据仍写在同一个底层 InMemory store 上。 */
  private static final class TrackingStore implements HarnessStore {

    private final InMemoryHarnessStore delegate;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    TrackingStore(InMemoryHarnessStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      return delegate.transaction(
          tx -> {
            depth.set(depth.get() + 1);
            try {
              return callback.apply(tx);
            } finally {
              depth.set(depth.get() - 1);
            }
          });
    }

    boolean insideTransaction() {
      return depth.get() > 0;
    }
  }

  /** 记录询问时的事务位置与意图是否真的执行。 */
  private static final class RecordingAdmission implements WorkDispatchAdmission {

    private final TrackingStore store;
    private final boolean admit;
    private final List<Boolean> asksInsideStoreTransaction = new CopyOnWriteArrayList<>();
    private final List<Boolean> intentsInsideStoreTransaction = new CopyOnWriteArrayList<>();
    private final List<WorkDispatchRequest> requests = new CopyOnWriteArrayList<>();

    RecordingAdmission(TrackingStore store, boolean admit) {
      this.store = store;
      this.admit = admit;
    }

    @Override
    public <T> Optional<T> executeIfAdmitted(WorkDispatchRequest request, Supplier<T> intent) {
      requests.add(request);
      asksInsideStoreTransaction.add(store.insideTransaction());
      if (!admit) {
        // 宿主拒绝：绝不执行意图（生产实现同样只在判定通过后才调用 intent）。
        return Optional.empty();
      }
      T outcome = intent.get();
      intentsInsideStoreTransaction.add(store.insideTransaction());
      return Optional.ofNullable(outcome);
    }
  }

  /** 记录外部调用发生在什么事务位置，以及调用时已经 durable 的 invocation 状态。 */
  private static final class RecordingGateway implements ToolGateway {

    private final TrackingStore store;
    private final List<UUID> started = new CopyOnWriteArrayList<>();
    private final List<Boolean> startsInsideStoreTransaction = new CopyOnWriteArrayList<>();
    private final List<Boolean> preflightsInsideStoreTransaction = new CopyOnWriteArrayList<>();
    private final List<ToolInvocationStatus> statusAtStart = new CopyOnWriteArrayList<>();
    private final InMemoryHarnessStore stateStore;

    RecordingGateway(TrackingStore store, InMemoryHarnessStore stateStore) {
      this.store = store;
      this.stateStore = stateStore;
    }

    @Override
    public PreflightResult preflight(ToolInvocationRequest request) {
      preflightsInsideStoreTransaction.add(store.insideTransaction());
      return new ToolGateway.Allow();
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      startsInsideStoreTransaction.add(store.insideTransaction());
      statusAtStart.add(
          ToolProcessorTestSupport.tool(stateStore, execution.invocationId()).status());
      started.add(execution.invocationId());
      return new ToolGateway.RetryLater(ADMISSION_DEFERRAL);
    }
  }

  private record Wiring(
      ToolProcessor processor,
      ClaimedWork claim,
      InMemoryHarnessStore store,
      RecordingGateway gateway,
      RecordingAdmission admission,
      UUID toolInvocationId) {}

  private Wiring wiring(boolean admit) {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ToolProcessorTestSupport.Baseline baseline =
        ToolProcessorTestSupport.seedToolBaseline(store, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.Scenario scenario =
        ToolProcessorTestSupport.bashScenario(ToolSideEffect.READ_ONLY);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", scenario.toolName(), scenario.argumentsJson()),
            scenario.binding());
    ToolProcessorTestSupport.Seeded seeded =
        ToolProcessorTestSupport.seedTool(
            store, baseline, request, ToolProcessorTestSupport.NOW, scenario);
    ClaimedWork claim =
        ToolProcessorTestSupport.claim(
            store, seeded.toolInvocationId(), ToolProcessorTestSupport.NOW);
    TrackingStore tracking = new TrackingStore(store);
    RecordingGateway gateway = new RecordingGateway(tracking, store);
    RecordingAdmission admission = new RecordingAdmission(tracking, admit);
    ToolProcessor processor =
        new ToolProcessor(
            tracking,
            gateway,
            SINK,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY,
                ADMISSION_DEFERRAL),
            Clock.fixed(ToolProcessorTestSupport.NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            admission);
    return new Wiring(processor, claim, store, gateway, admission, seeded.toolInvocationId());
  }

  @Test
  void deniedToolDispatchIsDecidedInsideTheStoreTransactionBeforeAnyExternalCall() {
    Wiring wiring = wiring(false);

    assertEquals(ProcessResult.RESCHEDULED, wiring.processor().process(wiring.claim()));

    // 请求在任何 Harness 行锁/状态转换之前、且与状态转换意图同处一个 store 事务边界。
    assertEquals(List.of(Boolean.TRUE), wiring.admission().asksInsideStoreTransaction);
    assertEquals(1, wiring.admission().requests.size());
    // 拒绝：意图一次都没有执行，invocation 事实完全不变。
    assertTrue(wiring.admission().intentsInsideStoreTransaction.isEmpty());
    ToolInvocation tool = ToolProcessorTestSupport.tool(wiring.store(), wiring.toolInvocationId());
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertNull(tool.approval());
    // 外部权限评估（preflight）在事务外；对外执行（start）根本没发生。
    assertEquals(List.of(Boolean.FALSE), wiring.gateway().preflightsInsideStoreTransaction);
    assertTrue(wiring.gateway().started.isEmpty());
    assertFalse(wiring.gateway().startsInsideStoreTransaction.contains(Boolean.TRUE));
    // Work 只延后执行：lease 归还并按 admissionDeferral durable 重排。
    Work work = ToolProcessorTestSupport.toolWork(wiring.store(), wiring.toolInvocationId());
    assertNull(work.leaseToken());
    assertEquals(ToolProcessorTestSupport.NOW.plus(ADMISSION_DEFERRAL), work.availableAt());
  }

  @Test
  void grantedToolDispatchCommitsDispatchingWithTheGrantAndStartsTheGatewayOnlyAfterCommit() {
    Wiring wiring = wiring(true);

    assertEquals(ProcessResult.RESCHEDULED, wiring.processor().process(wiring.claim()));

    assertEquals(List.of(Boolean.TRUE), wiring.admission().asksInsideStoreTransaction);
    assertEquals(List.of(Boolean.TRUE), wiring.admission().intentsInsideStoreTransaction);
    assertEquals(1, wiring.admission().requests.size());
    // 先提交再对外调用：preflight 与 start 都不在任何 store 事务里，且 start 时持久化状态已经是 DISPATCHING。
    assertEquals(List.of(Boolean.FALSE), wiring.gateway().preflightsInsideStoreTransaction);
    assertEquals(List.of(Boolean.FALSE), wiring.gateway().startsInsideStoreTransaction);
    assertEquals(List.of(wiring.toolInvocationId()), wiring.gateway().started);
    assertEquals(List.of(ToolInvocationStatus.DISPATCHING), wiring.gateway().statusAtStart);
    // RetryLater 肯定未开始：安全回退到 READY 并重排等待下一次派发。
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(wiring.store(), wiring.toolInvocationId()).status());
    Work work = ToolProcessorTestSupport.toolWork(wiring.store(), wiring.toolInvocationId());
    assertNull(work.leaseToken());
  }
}
