package fun.fengwk.kkstudio.harness.infra.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchAdmission;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Work dispatcher：{@code HarnessStore.Transaction.claimNextWork} 的首个生产调用方。
 *
 * <p>职责边界（KISS）：dispatcher 只做 Work claim、按类型路由、宿主派发准入、bounded handoff、合并 wake、periodic poll 与 stop
 * 生命周期。它绝不解释 Processor 的 typed 结果改写 durable 状态 —— handoff 类型是 {@link Consumer}，Processor 的 complete
 * / reschedule / delete 由 Processor 自己在所有权围栏保护下决定。
 *
 * <p>宿主派发准入：claim 之后、handoff 之前，dispatcher 只对「可能首次对外执行」的 claim（READY 的 MODEL / TOOL 调用）用 {@link
 * WorkDispatchAdmission} 询问宿主是否允许开始新的对外执行；判定需要在只读短事务内解析该调用的 Thread / Session / 冻结工具绑定（不写任何 durable
 * 事实），拒绝时按 {@code admissionDeferral} 归还并重排 claim —— 绝不派发、绝不丢弃、绝不改写 invocation。在途（
 * DISPATCHING/RUNNING）、等待态、终态与 THREAD claim 结构性放行：它们不会开始新的对外执行，拒绝只会让在途执行无法收尾。没有宿主策略时 使用 {@link
 * WorkDispatchAdmission#ALLOW_ALL}。
 *
 * <p>Work claim 协议：每次 claim 使用 {@link HarnessStoreTime#millisecondClock} 包装后的新毫秒 now、新 UUID token
 * 与对应类型的 leaseDuration，并向底层传递当前 dispatcher 实例的 {@link #nodeInstanceId}，以驱动 Environment route
 * 亲和性路由围栏； THREAD / MODEL / TOOL 按固定显式列表 round-robin 轮询，cursor 是实例字段并跨 wake / drain
 * 保留（maxDispatchTasks=1 时也不会持续偏爱 THREAD）。 每个 claim 成功后必须二选一：worker executor 已接受 handoff
 * task；或通过所有权围栏归还（同一短事务先由 {@code lockClaimedWork} 确认仍 owned，才按正 {@code executorRejectionDelay} 执行
 * {@code rescheduleWork}，lost 则 no-op；清理失败只 log，保留 lease 待过期后由其他实例恢复）。 executor rejection 后立即结束当前
 * drain，禁止 claim-&gt;reject 热循环。
 *
 * <p>单 drain 协议：drain 任务由注入的单线程 {@code drainExecutor} 串行执行；{@code wakeRequested} + {@code
 * drainRunning} CAS + finally recheck 保证并发 wake 合并为单 drain 且不丢 wake —— drain 开始即清
 * wakeRequested，drain 内到达的 wake 由 do-while 吸收， drain 收尾窗口到达的 wake 由 finally recheck 重新提交。periodic
 * poll 用 fixed-delay 调度，{@link #stop} 取消 future。
 *
 * <p>Processor handoff task 语义：task finally 总是释放本地 capacity 并再次 wake；Processor {@code process} 抛
 * RuntimeException 时只在 dispatcher 边界记录一条 ERROR（target type/id、lease token/deadline、claimed wake
 * version、required environment、原始异常栈），既不重复记录，也绝不猜测 complete / reschedule / delete（保留 lease
 * 待过期恢复）；Error 不吞但 finally 必须执行。{@link #stop} 不 shutdown 注入的 drain / worker / poll executor，不
 * interrupt 已接受 task，不等待 Processor task 完成；stop 后不启动新的 drain iteration，已进入数据库的 claim 若在 handoff
 * 前观察到 stop，则会被 ownership-fenced 所有权围栏归还。
 *
 * <p>三个注入 executor 全部由调用方持有生命周期；drainExecutor 必须串行执行任务（例如单线程池），workerExecutor 的队列 + 运行中 handoff
 * task 总数由 {@code maxDispatchTasks} 约束。两个 {@link Executor} 必须使用 fail-fast submission contract：成功
 * {@code execute} 必须恰好接受一次 task，无法接受时必须在接受前同步抛出；禁止 CallerRunsPolicy、DiscardPolicy、
 * DiscardOldestPolicy 或任何静默丢弃 / 替换已接受 task 的实现。生命周期关闭顺序必须先 {@link #stop} dispatcher，再 shutdown
 * executors。
 */
public final class HarnessWorkDispatcher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HarnessWorkDispatcher.class);

  public static final String CHANNEL = "harness_runtime_work";

  private static final List<WorkTargetType> ROUND_ROBIN_TYPES =
      List.of(WorkTargetType.THREAD, WorkTargetType.MODEL, WorkTargetType.TOOL);

  /** 当前 Dispatcher 实例在节点集群中的唯一标识，传递给底层用于 Environment route 亲和性路由围栏校验。 */
  private final UUID nodeInstanceId;

  private final HarnessStore store;
  private final HarnessWorkDispatcherConfig config;
  private final Clock clock;
  private final Executor drainExecutor;
  private final Executor workerExecutor;
  private final ScheduledExecutorService pollScheduler;
  private final Map<WorkTargetType, Consumer<ClaimedWork>> handlers;
  private final WorkDispatchAdmission admission;
  private final AtomicBoolean wakeRequested = new AtomicBoolean();
  private final AtomicBoolean drainRunning = new AtomicBoolean();
  private final AtomicInteger dispatchCapacity = new AtomicInteger();
  private final Object lifecycleLock = new Object();
  private volatile boolean started;
  private volatile boolean stopped;
  private int roundRobinCursor;
  private ScheduledFuture<?> pollFuture;

  /**
   * 生产 wiring 构造：按 {@link WorkTargetType} 把 claim 路由给对应 Processor 的 {@code process}；返回值
   * 被有意忽略（dispatcher 绝不解释 typed 结果）。自动分配随机 nodeInstanceId。
   */
  public HarnessWorkDispatcher(
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor,
      WorkDispatchAdmission admission) {
    this(
        UUID.randomUUID(),
        store,
        config,
        clock,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        threadProcessor,
        modelProcessor,
        toolProcessor,
        admission);
  }

  /** 显式指定 nodeInstanceId 的生产 wiring 构造，用于绑定节点特定的 Environment route 路由围栏。 */
  public HarnessWorkDispatcher(
      UUID nodeInstanceId,
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor,
      WorkDispatchAdmission admission) {
    this(
        nodeInstanceId,
        store,
        config,
        clock,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        routing(
            Objects.requireNonNull(threadProcessor, "threadProcessor")::process,
            Objects.requireNonNull(modelProcessor, "modelProcessor")::process,
            Objects.requireNonNull(toolProcessor, "toolProcessor")::process),
        admission);
  }

  /** 包级组合 / 测试构造：直接把 claim handoff 给三个显式 consumer（全部 fail-fast non-null）。自动分配随机 nodeInstanceId。 */
  HarnessWorkDispatcher(
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler,
      WorkDispatchAdmission admission) {
    this(
        UUID.randomUUID(),
        store,
        config,
        clock,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        threadHandler,
        modelHandler,
        toolHandler,
        admission);
  }

  /** 显式指定 nodeInstanceId 的包级组合 / 测试构造。 */
  HarnessWorkDispatcher(
      UUID nodeInstanceId,
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler,
      WorkDispatchAdmission admission) {
    this(
        nodeInstanceId,
        store,
        config,
        clock,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        routing(
            Objects.requireNonNull(threadHandler, "threadHandler"),
            Objects.requireNonNull(modelHandler, "modelHandler"),
            Objects.requireNonNull(toolHandler, "toolHandler")),
        admission);
  }

  private HarnessWorkDispatcher(
      UUID nodeInstanceId,
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Map<WorkTargetType, Consumer<ClaimedWork>> handlers,
      WorkDispatchAdmission admission) {
    this.nodeInstanceId = Objects.requireNonNull(nodeInstanceId, "nodeInstanceId");
    this.store = Objects.requireNonNull(store, "store");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
    this.drainExecutor = requireFailFastExecutor(drainExecutor, "drainExecutor");
    this.workerExecutor = requireFailFastExecutor(workerExecutor, "workerExecutor");
    this.pollScheduler = Objects.requireNonNull(pollScheduler, "pollScheduler");
    this.handlers = handlers;
    this.admission = Objects.requireNonNull(admission, "admission");
  }

  public UUID nodeInstanceId() {
    return nodeInstanceId;
  }

  private static Map<WorkTargetType, Consumer<ClaimedWork>> routing(
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler) {
    Map<WorkTargetType, Consumer<ClaimedWork>> handlers = new EnumMap<>(WorkTargetType.class);
    handlers.put(WorkTargetType.THREAD, threadHandler);
    handlers.put(WorkTargetType.MODEL, modelHandler);
    handlers.put(WorkTargetType.TOOL, toolHandler);
    return Collections.unmodifiableMap(handlers);
  }

  /**
   * 启动调度：注册 fixed-delay periodic poll 并触发首次 wake。幂等（重复调用只注册一次）；已 stop 后调用抛 {@link
   * IllegalStateException}。
   */
  public void start() {
    synchronized (lifecycleLock) {
      if (stopped) {
        throw new IllegalStateException("dispatcher is already stopped");
      }
      if (pollFuture != null) {
        return;
      }
      long intervalMillis = config.periodicPollInterval().toMillis();
      pollFuture =
          pollScheduler.scheduleWithFixedDelay(
              this::poll, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
      started = true;
      wake();
    }
  }

  /** 请求一次 drain；start 前或 stop 后为 no-op。 */
  public void wake() {
    if (!started || stopped) {
      return;
    }
    wakeRequested.set(true);
    if (drainRunning.compareAndSet(false, true)) {
      submitDrain();
    }
  }

  /**
   * 停止调度：取消 periodic poll future，之后不再启动新的 drain iteration；已经进入数据库的 claim 可能提交，但会在 handoff 前归还。不
   * shutdown 注入 executor、不 interrupt 已接受 task、不等待 Processor task 完成。幂等。
   */
  public void stop() {
    synchronized (lifecycleLock) {
      if (stopped) {
        return;
      }
      stopped = true;
      started = false;
      ScheduledFuture<?> future = pollFuture;
      if (future != null) {
        future.cancel(false);
      }
    }
  }

  @Override
  public void close() {
    stop();
  }

  private void poll() {
    wake();
  }

  private void submitDrain() {
    AtomicBoolean taskStarted = new AtomicBoolean();
    try {
      drainExecutor.execute(
          () -> {
            taskStarted.set(true);
            runDrain();
          });
    } catch (RuntimeException error) {
      if (taskStarted.get()) {
        throw error;
      }
      drainRunning.set(false);
      log.warn(
          "drain executor failed to accept a drain task; the next wake or poll will retry", error);
    } catch (Error error) {
      if (!taskStarted.get()) {
        drainRunning.set(false);
      }
      throw error;
    }
  }

  private void runDrain() {
    try {
      do {
        wakeRequested.set(false);
        drainOnce();
      } while (!stopped && wakeRequested.get());
    } catch (RuntimeException error) {
      log.warn("work drain failed; the next wake or poll will retry", error);
    } finally {
      drainRunning.set(false);
      // drain 收尾窗口内到达的 wake：drainRunning 已清，必须由 recheck 重新提交，否则 wake 丢失。
      if (!stopped && wakeRequested.get() && drainRunning.compareAndSet(false, true)) {
        submitDrain();
      }
    }
  }

  /** 单次 drain 扫描：按 round-robin 顺序 claim，直到 capacity 满、无 due 或需要结束。 */
  private void drainOnce() {
    int consecutiveEmpty = 0;
    while (!stopped && dispatchCapacity.get() < config.maxDispatchTasks()) {
      WorkTargetType type = nextRoundRobinType();
      ClaimedWork claim = claimNext(type);
      if (claim == null) {
        if (++consecutiveEmpty == ROUND_ROBIN_TYPES.size()) {
          break;
        }
        continue;
      }
      consecutiveEmpty = 0;
      if (stopped) {
        returnClaim(claim);
        break;
      }
      if (!admits(claim)) {
        // 宿主拒绝派发：只延后执行（按 admissionDeferral 重排），Work 与 invocation 事实保持不变。
        deferClaim(claim);
        continue;
      }
      if (!handoff(claim)) {
        // worker executor rejection：结束当前 drain，禁止 claim -> reject 热循环。
        returnClaim(claim);
        break;
      }
    }
  }

  private WorkTargetType nextRoundRobinType() {
    WorkTargetType type = ROUND_ROBIN_TYPES.get(roundRobinCursor);
    roundRobinCursor = (roundRobinCursor + 1) % ROUND_ROBIN_TYPES.size();
    return type;
  }

  private ClaimedWork claimNext(WorkTargetType type) {
    Instant now = clock.instant();
    String token = UUID.randomUUID().toString();
    Instant leaseUntil = now.plus(config.leaseDuration(type));
    return store.transaction(
        tx -> tx.claimNextWork(type, now, token, leaseUntil, nodeInstanceId).orElse(null));
  }

  private boolean handoff(ClaimedWork claim) {
    AtomicBoolean taskStarted = new AtomicBoolean();
    dispatchCapacity.incrementAndGet();
    try {
      workerExecutor.execute(
          () -> {
            taskStarted.set(true);
            runHandoff(claim);
          });
      return true;
    } catch (RuntimeException error) {
      if (taskStarted.get()) {
        throw error;
      }
      dispatchCapacity.decrementAndGet();
      log.warn(
          "worker executor failed to accept dispatch task for {}; returning the claim",
          claim.target(),
          error);
      return false;
    } catch (Error error) {
      if (!taskStarted.get()) {
        dispatchCapacity.decrementAndGet();
        log.error(
            "worker executor failed before accepting dispatch task for {}; returning the claim",
            claim.target(),
            error);
        try {
          returnClaim(claim);
        } finally {
          throw error;
        }
      }
      throw error;
    }
  }

  private void runHandoff(ClaimedWork claim) {
    try {
      Consumer<ClaimedWork> handler = handlers.get(claim.target().type());
      try {
        handler.accept(claim);
      } catch (RuntimeException error) {
        // 绝不猜测 complete / reschedule / delete：保留 lease 待过期后由后续 claim 恢复。
        log.error(
            "processor failed for target type={} id={}; leaseToken={} leaseUntil={} "
                + "claimedWakeVersion={} requiredEnvironmentId={}; its lease is left to expire so "
                + "the work can be recovered",
            claim.target().type(),
            claim.target().id(),
            claim.leaseToken(),
            claim.leaseUntil(),
            claim.claimedWakeVersion(),
            claim.requiredEnvironmentId(),
            error);
      }
    } finally {
      dispatchCapacity.decrementAndGet();
      wake();
    }
  }

  /**
   * 宿主派发准入：只对「可能首次对外执行」的 claim 询问宿主策略。
   *
   * <p>只读解析（不写 durable 事实）：READY 的 MODEL / TOOL 调用解析出所属 Thread 与 Session 后交给宿主。其余 claim 结构性放行：
   * 在途（DISPATCHING/RUNNING）只观察或收敛已有对外执行、等待态与终态只收敛 durable 事实、THREAD 只物化历史与规划 turn；拒绝这些会让
   * 暂停/阻塞永远无法安全收尾，却阻止不了任何新的对外调用。Work 对应的调用行已经不存在的（例如已被处理或删除）无法判定归属，交由 Processor 自己的所有权围栏处理，因此放行而不吞掉
   * claim。
   */
  private boolean admits(ClaimedWork claim) {
    WorkTarget target = claim.target();
    Optional<WorkDispatchRequest> request =
        store.transaction(tx -> resolveNewExecution(tx, target));
    return request.isEmpty() || admission.admits(request.get());
  }

  /** READY 调用才可能首次对外执行；返回空表示该 claim 不需要宿主判定。 */
  private static Optional<WorkDispatchRequest> resolveNewExecution(
      HarnessStore.Transaction tx, WorkTarget target) {
    return switch (target.type()) {
      case THREAD -> Optional.empty();
      case MODEL -> {
        ModelInvocation model = tx.findModelInvocation(target.id()).orElse(null);
        if (model == null || model.status() != ModelInvocationStatus.READY) {
          yield Optional.empty();
        }
        yield sessionOf(tx, model.threadId())
            .map(
                sessionId ->
                    new WorkDispatchRequest(
                        WorkTargetType.MODEL, model.id(), model.threadId(), sessionId, null));
      }
      case TOOL -> {
        ToolInvocation tool = tx.findToolInvocation(target.id()).orElse(null);
        if (tool == null || tool.status() != ToolInvocationStatus.READY) {
          yield Optional.empty();
        }
        ModelInvocation model = tx.findModelInvocation(tool.modelInvocationId()).orElse(null);
        if (model == null) {
          yield Optional.empty();
        }
        yield sessionOf(tx, model.threadId())
            .map(
                sessionId ->
                    new WorkDispatchRequest(
                        WorkTargetType.TOOL,
                        tool.id(),
                        model.threadId(),
                        sessionId,
                        tool.binding()));
      }
    };
  }

  private static Optional<UUID> sessionOf(HarnessStore.Transaction tx, UUID threadId) {
    return tx.findThread(threadId).map(ThreadState::sessionId);
  }

  /** 宿主拒绝后的归还重排（Ownership-fenced）：同短事务校验仍 owned 才按 admissionDeferral 重排；lost 则 no-op。 */
  private void deferClaim(ClaimedWork claim) {
    try {
      store.transaction(
          tx -> {
            Instant now = clock.instant();
            if (tx.lockClaimedWork(claim, now).isEmpty()) {
              return null;
            }
            tx.rescheduleWork(claim, now, now.plus(config.admissionDeferral()));
            return null;
          });
    } catch (RuntimeException error) {
      log.warn(
          "failed to defer claim {}; its lease will expire and the work will be re-claimed",
          claim.target(),
          error);
    }
  }

  /** 所有权围栏归还（Ownership-fenced）：同短事务 lockClaimedWork 校验仍 owned 才按正延迟 reschedule；lost 则 no-op。 */
  private void returnClaim(ClaimedWork claim) {
    try {
      store.transaction(
          tx -> {
            Instant now = clock.instant();
            if (tx.lockClaimedWork(claim, now).isEmpty()) {
              return null;
            }
            tx.rescheduleWork(claim, now, now.plus(config.executorRejectionDelay()));
            return null;
          });
    } catch (RuntimeException error) {
      log.warn(
          "failed to return claim {}; its lease will expire and the work will be re-claimed",
          claim.target(),
          error);
    }
  }

  private static Executor requireFailFastExecutor(Executor executor, String name) {
    Objects.requireNonNull(executor, name);
    if (executor instanceof ThreadPoolExecutor threadPool) {
      RejectedExecutionHandler handler = threadPool.getRejectedExecutionHandler();
      if (handler instanceof ThreadPoolExecutor.CallerRunsPolicy
          || handler instanceof ThreadPoolExecutor.DiscardPolicy
          || handler instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
        throw new IllegalArgumentException(name + " must use a fail-fast rejection policy");
      }
    }
    return executor;
  }
}
