package fun.fengwk.kkstudio.harness.infra.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.common.concurrent.BoundedWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * Work dispatcher：{@code HarnessStore.Transaction.claimNextWork} 的首个生产调用方。
 *
 * <p>职责边界（KISS）：dispatcher 只做 Work claim、按类型路由、bounded handoff、合并 wake、periodic poll 与 stop
 * 生命周期。它绝不解释 Processor 的 typed 结果改写 durable 状态 —— handoff 类型是 {@link Consumer}，Processor 的 complete
 * / reschedule / delete 由 Processor 自己在所有权围栏保护下决定。
 *
 * <p>Work claim 协议：每次 claim 使用新 UUID token 与对应类型的 leaseDuration，并向底层传递当前 dispatcher 实例的 {@link
 * #nodeInstanceId}，以驱动 Environment route 亲和性路由围栏； THREAD / MODEL / TOOL 按固定显式列表 round-robin
 * 轮询，cursor 是实例字段并跨 wake / drain 保留（maxDispatchTasks=1 时也不会持续偏爱 THREAD）。 每个 claim 成功后必须二选一：worker
 * executor 已接受 handoff task；或通过所有权围栏归还（同一短事务先由 {@code lockClaimedWork} 确认仍 owned，才按正 {@code
 * executorRejectionDelay} 执行 {@code rescheduleWork}，lost 则 no-op；清理失败只 log，保留 lease 待过期后由其他实例恢复）。
 * executor rejection 后立即结束当前 drain，禁止 claim-&gt;reject 热循环。
 *
 * <p>调度骨架（合并 wake 的单 drain、bounded handoff、stop 回收、容量计数与 fail-fast executor 契约）由共享的 {@link
 * BoundedWorkDispatcher} 提供；本类只注入 Harness 专有的 claim 轮转、Processor handoff 与 ownership-fenced 归还。
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

  private static final String NAME = "harness work dispatcher";

  private static final List<WorkTargetType> ROUND_ROBIN_TYPES =
      List.of(WorkTargetType.THREAD, WorkTargetType.MODEL, WorkTargetType.TOOL);

  /** 当前 Dispatcher 实例在节点集群中的唯一标识，传递给底层用于 Environment route 亲和性路由围栏校验。 */
  private final UUID nodeInstanceId;

  private final HarnessStore store;
  private final HarnessWorkDispatcherConfig config;
  private final Map<WorkTargetType, Consumer<ClaimedWork>> handlers;
  private final BoundedWorkDispatcher<ClaimedWork> dispatcher;
  private int roundRobinCursor;

  /**
   * 生产 wiring 构造：按 {@link WorkTargetType} 把 claim 路由给对应 Processor 的 {@code process}；返回值
   * 被有意忽略（dispatcher 绝不解释 typed 结果）。自动分配随机 nodeInstanceId。
   */
  public HarnessWorkDispatcher(
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    this(
        UUID.randomUUID(),
        store,
        config,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        threadProcessor,
        modelProcessor,
        toolProcessor);
  }

  /** 显式指定 nodeInstanceId 的生产 wiring 构造，用于绑定节点特定的 Environment route 路由围栏。 */
  public HarnessWorkDispatcher(
      UUID nodeInstanceId,
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      ThreadProcessor threadProcessor,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    this(
        nodeInstanceId,
        store,
        config,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        routing(
            Objects.requireNonNull(threadProcessor, "threadProcessor")::process,
            Objects.requireNonNull(modelProcessor, "modelProcessor")::process,
            Objects.requireNonNull(toolProcessor, "toolProcessor")::process));
  }

  /** 包级组合 / 测试构造：直接把 claim handoff 给三个显式 consumer（全部 fail-fast non-null）。自动分配随机 nodeInstanceId。 */
  HarnessWorkDispatcher(
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler) {
    this(
        UUID.randomUUID(),
        store,
        config,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        threadHandler,
        modelHandler,
        toolHandler);
  }

  /** 显式指定 nodeInstanceId 的包级组合 / 测试构造。 */
  HarnessWorkDispatcher(
      UUID nodeInstanceId,
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Consumer<ClaimedWork> threadHandler,
      Consumer<ClaimedWork> modelHandler,
      Consumer<ClaimedWork> toolHandler) {
    this(
        nodeInstanceId,
        store,
        config,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        routing(
            Objects.requireNonNull(threadHandler, "threadHandler"),
            Objects.requireNonNull(modelHandler, "modelHandler"),
            Objects.requireNonNull(toolHandler, "toolHandler")));
  }

  private HarnessWorkDispatcher(
      UUID nodeInstanceId,
      HarnessStore store,
      HarnessWorkDispatcherConfig config,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      Map<WorkTargetType, Consumer<ClaimedWork>> handlers) {
    this.nodeInstanceId = Objects.requireNonNull(nodeInstanceId, "nodeInstanceId");
    this.store = Objects.requireNonNull(store, "store");
    this.config = Objects.requireNonNull(config, "config");
    this.handlers = handlers;
    this.dispatcher =
        new BoundedWorkDispatcher<>(
            NAME,
            config.maxDispatchTasks(),
            config.periodicPollInterval(),
            drainExecutor,
            workerExecutor,
            pollScheduler,
            this::claimNext,
            this::runHandoff,
            this::returnClaim);
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
    dispatcher.start();
  }

  /** 请求一次 drain；start 前或 stop 后为 no-op。 */
  public void wake() {
    dispatcher.wake();
  }

  /**
   * 停止调度：取消 periodic poll future，之后不再启动新的 drain iteration；已经进入数据库的 claim 可能提交，但会在 handoff 前归还。不
   * shutdown 注入 executor、不 interrupt 已接受 task、不等待 Processor task 完成。幂等。
   */
  public void stop() {
    dispatcher.stop();
  }

  @Override
  public void close() {
    dispatcher.close();
  }

  /**
   * 按实例级 round-robin 游标扫描 THREAD / MODEL / TOOL：单次调用最多扫描三种类型，返回首个成功 claim；全部无 due 时返回 {@code
   * null}。游标每扫描一个类型即前进一格，命中时已指向其后一个类型，全 empty 时回到原值并跨 wake / drain 保留。
   */
  private ClaimedWork claimNext() {
    for (int scanned = 0; scanned < ROUND_ROBIN_TYPES.size(); scanned++) {
      WorkTargetType type = ROUND_ROBIN_TYPES.get(roundRobinCursor);
      roundRobinCursor = (roundRobinCursor + 1) % ROUND_ROBIN_TYPES.size();
      ClaimedWork claim = claimNext(type);
      if (claim != null) {
        return claim;
      }
    }
    return null;
  }

  private ClaimedWork claimNext(WorkTargetType type) {
    String token = UUID.randomUUID().toString();
    return store.transaction(
        tx ->
            tx.claimNextWork(type, token, config.leaseDuration(type), nodeInstanceId).orElse(null));
  }

  private void runHandoff(ClaimedWork claim) {
    try {
      Consumer<ClaimedWork> handler = handlers.get(claim.target().type());
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
  }

  /** 所有权围栏归还（Ownership-fenced）：同短事务 lockClaimedWork 校验仍 owned 才按正延迟 reschedule；lost 则 no-op。 */
  private void returnClaim(ClaimedWork claim) {
    try {
      store.transaction(
          tx -> {
            if (tx.lockClaimedWork(claim).isEmpty()) {
              return null;
            }
            tx.rescheduleWork(claim, config.executorRejectionDelay());
            return null;
          });
    } catch (RuntimeException error) {
      log.warn(
          "failed to return claim {}; its lease will expire and the work will be re-claimed",
          claim.target(),
          error);
    }
  }
}
