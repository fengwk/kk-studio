package fun.fengwk.kkstudio.harness.infra.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>职责边界（KISS）：dispatcher 只做 claim、按类型路由、bounded handoff、合并 wake、periodic poll 与 lifecycle。它绝不读取
 * Thread / Invocation business state（claim 是 Work-only 短事务），绝不解释 Processor 的 typed 结果改写 durable 状态
 * —— handoff 类型是 {@link Consumer}，Processor 的 complete / reschedule / delete 由 Processor 自己决定。
 *
 * <p>claim 协议：每次 claim 使用 {@link HarnessStoreTime#millisecondClock} 包装后的新毫秒 now、新 UUID token 与对应类型的
 * leaseDuration；THREAD / MODEL / TOOL 按固定显式列表 round-robin，cursor 是实例字段并 跨 wake / drain
 * 保留（maxDispatchTasks=1 时也不会持续偏爱 THREAD）。每个 claim 成功后必须二选一：worker executor 已接受 handoff task；或
 * ownership-fenced 归还（同一短事务先 {@code lockClaimedWork}，仍 owned 才按正 {@code executorRejectionDelay}
 * {@code rescheduleWork}，lost 则 no-op；清理失败只 log，保留 lease 待过期后由其他实例恢复）。executor rejection 后立即结束当前
 * drain，禁止 claim-&gt;reject 热循环。
 *
 * <p>单 drain 协议：drain 任务由注入的单线程 {@code drainExecutor} 串行执行；{@code wakeRequested} + {@code
 * drainRunning} CAS + finally recheck 保证并发 wake 只触发单 drain 且不丢 wake —— drain 开始即清
 * wakeRequested，drain 内到达的 wake 由 do-while 吸收，drain 收尾窗口到达的 wake 由 finally recheck 重新提交。 periodic
 * poll 用 fixed-delay 调度，{@link #stop} 取消 future。
 *
 * <p>Processor handoff task 语义：task finally 总是释放本地 capacity 并再次 wake；Processor {@code process} 抛
 * RuntimeException 只 log，绝不猜测 complete / reschedule / delete（保留 lease 待过期恢复）；Error 不吞但 finally
 * 必须执行。{@link #stop} 不 shutdown 注入的 drain / worker / poll executor，不 interrupt 已接受 task，不等待
 * Processor task 完成；stop 后不启动新的 drain iteration，已进入数据库的 claim 若在 handoff 前观察到 stop，则会被
 * ownership-fenced 归还。
 *
 * <p>三个注入 executor 全部由调用方持有生命周期；drainExecutor 必须串行执行任务（例如单线程池），workerExecutor 的队列 + 运行中 handoff
 * task 总数由 {@code maxDispatchTasks} 约束。两个 {@link Executor} 必须使用 fail-fast submission contract：成功
 * {@code execute} 必须恰好接受一次 task，无法接受时必须在接受前同步抛出；禁止 CallerRunsPolicy、DiscardPolicy、
 * DiscardOldestPolicy 或任何静默丢弃 / 替换已接受 task 的实现。生命周期关闭顺序必须先 {@link #stop} dispatcher，再 shutdown
 * executors。
 */
public final class HarnessWorkDispatcher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HarnessWorkDispatcher.class);
  private static final List<WorkTargetType> ROUND_ROBIN_TYPES =
      List.of(WorkTargetType.THREAD, WorkTargetType.MODEL, WorkTargetType.TOOL);

  private final HarnessStore store;
  private final HarnessWorkDispatcherConfig config;
  private final Clock clock;
  private final Executor drainExecutor;
  private final Executor workerExecutor;
  private final ScheduledExecutorService pollScheduler;
  private final Map<WorkTargetType, Consumer<ClaimedWork>> handlers;
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
   * 被有意忽略（dispatcher 绝不解释 typed 结果）。
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
      ToolProcessor toolProcessor) {
    this(
        store,
        config,
        clock,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        routing(
            Objects.requireNonNull(threadProcessor, "threadProcessor")::process,
            Objects.requireNonNull(modelProcessor, "modelProcessor")::process,
            Objects.requireNonNull(toolProcessor, "toolProcessor")::process));
  }

  /** 包级组合 / 测试构造：直接把 claim handoff 给三个显式 consumer（全部 fail-fast non-null）。 */
  HarnessWorkDispatcher(
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler) {
    this(
        store,
        config,
        clock,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        routing(
            Objects.requireNonNull(threadHandler, "threadHandler"),
            Objects.requireNonNull(modelHandler, "modelHandler"),
            Objects.requireNonNull(toolHandler, "toolHandler")));
  }

  private HarnessWorkDispatcher(
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Clock clock,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Map<WorkTargetType, Consumer<ClaimedWork>> handlers) {
    this.store = Objects.requireNonNull(store, "store");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
    this.drainExecutor = requireFailFastExecutor(drainExecutor, "drainExecutor");
    this.workerExecutor = requireFailFastExecutor(workerExecutor, "workerExecutor");
    this.pollScheduler = Objects.requireNonNull(pollScheduler, "pollScheduler");
    this.handlers = handlers;
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
    return store.transaction(tx -> tx.claimNextWork(type, now, token, leaseUntil).orElse(null));
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
            "processor failed for {}; its lease is left to expire so the work can be recovered",
            claim.target(),
            error);
      }
    } finally {
      dispatchCapacity.decrementAndGet();
      wake();
    }
  }

  /** Ownership-fenced 归还：同短事务 lockClaimedWork 仍 owned 才按正延迟 reschedule；lost 则 no-op。 */
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
