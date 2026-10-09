package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.ThreadInputDemand;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionChildStarter;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionResultEvaluator;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinProjector;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ResolvedRequestValidator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestReducer;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Target Thread processor：消费 dispatcher 已 claim 的 THREAD Work，每个 claim 恰好执行一个持久化 action 的
 * single-action reducer（KISS），下一 action 一律由同事务 {@code requestWork} 驱动，绝不内部循环。
 *
 * <p>处理流程：先用纯 {@link ThreadContextProbe} 把当前 Thread 分类为唯一的 live/historical 适用性上下文 （{@link
 * ThreadContext}，unlocked 读：Model 只按 (threadId, open TURN_START) 查找，Tool siblings 只在 Model 结果恰为当前
 * Assistant head 时加载），再按上下文恰执行一个动作：(a) MODEL_TERMINAL_PENDING —— head 恰为 basis 且未挂结果的 terminal
 * ModelInvocation 原子 apply；(b) TOOL_TERMINAL_PENDING —— 当前 head 恰为本 Thread ModelInvocation 产出的
 * Assistant Entry、且其 Tool siblings 全部 terminal 时按 callIndex 原子 apply；(c) MODEL_ACTIVE / TOOL_ACTIVE
 * —— 当前 applicable invocation 非 terminal 时完成 claim；(d) CONTINUATION_DUE —— HISTORY phase gap 启动
 * TURN_PREFIX，其它 continueModel obligation 启动 CONTINUATION；(e) queued USER_MESSAGE/CUSTOM_MESSAGE
 * 存在时启动 INPUT Turn（IDLE_OR_HISTORICAL）；(f) 否则完成 claim。旧 / 历史 open Turn 只在启动新 INPUT Turn 时被
 * normalization，绝不恢复 / 复用。不变量被破坏的形状由分类器以 ISE 拒绝，绝不降级为业务上下文。
 *
 * <p>Turn 启动采用 speculative plan：短事务按 Session KEY SHARE -&gt; Thread 顺序加锁、读取 queued Command 快照与
 * cutoff、校验 claim（并对近过期 lease 做 {@link ProcessorLeaseSupport#ensureLeaseMargin} 保证首次 Resolver
 * heartbeat 前不会过期）、分配 candidate Entry ID 并构造完整合法 candidate EntryPath（不写任何持久化状态）；事务外调用 {@link
 * TurnResolver}（期间由本地 {@link WorkHeartbeat} 维持 lease）；第二短事务以 source head / cutoff 内 Command 精确快照 /
 * claim ownership 做 CAS，一次性原子提交 TURN_START + Message + Command markers + Thread 更新 +
 * ModelInvocation/MODEL Work（resolved）或 AssistantError + FAILED TURN_END（rejected）。Resolved
 * 请求在提交前先经 {@link ResolvedRequestValidator} 按 candidate branch 事实（route / model / variant / tools /
 * compaction）做机械一致性校验，不一致即抛错且零持久化状态写入（绝不转 typed rejection）。任何 CAS / claim 损失一律抛内部 {@link
 * ClaimLostSignal} 使事务完整回滚（零部分写入），由 {@link #process} 映射为 LOST_OWNERSHIP；Resolver 异常 / null /
 * heartbeat 调度失败按单一正失败延迟 reschedule，绝不静默丢弃 Work。duplicate / stale THREAD claim 是 no-op。
 *
 * <p>锁序与最终所有权围栏（final fence）：Model terminal apply / Tool sibling batch / resolve commit
 * 都在同一事务内先完成全部低序写入（Session KEY SHARE -&gt; Thread -&gt; Commands -&gt; ModelInvocation -&gt;
 * ToolInvocation siblings），claimed THREAD Work 的最终所有权围栏最后执行；围栏失败抛出内部 {@link ClaimLostSignal}
 * 使事务完整回滚，再由 {@link #process} 映射为 LOST_OWNERSHIP，绝不存在带持久化变更的 LOST 提交。commit 与 applyModel 在同层 Work
 * 中按 (type, id) 升序请求（先 THREAD wake 再 MODEL / TOOL Work）。历史 / 非 applicable open Turn（head 不在
 * applicable 位置）在分类阶段只使用 unlocked 读，绝不先锁 Model/Tool 再落到 Commands / INPUT normalization。
 *
 * <p>每次成功 action 都在同一事务 complete 当前 THREAD claim；下一 action 已确定时先 {@code requestWork(THREAD)} 再
 * complete，依赖 wakeVersion 保留新 wake。Model terminal apply 按 planner 决策落地：active Tool phase 仅为 READY
 * 槽位请求 TOOL Work（全部 immediate terminal 则请求 THREAD 让 batch 分下一 claim 经 ToolTerminalPending 应用）；
 * closed turn（COMPLETE 无 calls / CONTINUE / LENGTH 无 calls / FILTERED / terminal failure / cancel /
 * unknown / compaction 关闭结果）在同一事务追加 TURN_END 与严格物化校验（attach-then-delete）并物理删除 ModelInvocation，且仅在已有
 * queued user demand、HISTORY / OVERFLOW obligation、fallback、hard overflow 或 CONTINUE 的
 * continueModel obligation 已确定时请求 THREAD；完全结束的 idle run 不因 soft threshold 自唤醒。失败 / 停止 / complete
 * COMPACTION 由 planner 判定不 spin。CONTINUE 与 Tool sibling batch 同构： 固定先请求 THREAD 再 complete，下一 claim
 * 才由 durable continuation 启动续写；Tool sibling batch 追加 outcome 后追加 continueModel=true TURN_END 并同事务删除
 * children+parent。resolve commit 的 resolved 只请求 MODEL Work，绝不因 deferred messages 制造无意义 THREAD claim
 * （terminal apply 会按 queued 快照重建 wake）；rejected 只在仍保留合法 deferred messages 时先请求 THREAD 再
 * complete，绝不立即重排同一压缩。
 *
 * <p>持久化变更时间会抬升到事务内已锁定 Thread/path/Model/Tool 事实的时间下界；Work ownership、renew、 complete、request 与
 * reschedule 始终使用未抬升的本地 lease clock，避免未来持久化时间改变 lease 语义。
 *
 * <p>压缩触发正交：soft threshold 在尚未完成的 continuation 边界或下一条真实 user demand 到达时门控；hard overflow 立即压缩并只恢复一次。
 * 所有 trigger 共用 MODEL Work、一次 fallback 与 deterministic no-gain；切分先 HISTORY 再以 continueModel
 * obligation 机械启动 TURN_PREFIX。 压缩消费零 queued Command，另一 Thread 拥有的共享历史 turn 不压缩。
 */
@Slf4j
public final class ThreadProcessor {

  /** 确定性 resolver 失败落成的稳定 durable AssistantError：不泄露 raw cause，只说明本轮规划失败与恢复方式。 */
  private static final AssistantError DETERMINISTIC_FAILURE =
      new AssistantError(
          "TURN_RESOLVE_FAILED",
          "Turn planning failed before the model was invoked. Send a new message to retry.");

  private final HarnessStore store;
  private final TurnResolver resolver;
  private final ThreadProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor heartbeatWorker;
  private final ModelOutcomeAppender modelOutcomeAppender;
  private final TurnPlanBuilder planBuilder = new TurnPlanBuilder();
  private final CommandHarvestReducer settingsReducer = new CommandHarvestReducer();
  private final ClaimAdmissionGuard admissionGuard = new ClaimAdmissionGuard();
  private final ThreadContextProbe threadContextProbe = new ThreadContextProbe();
  private final AutomaticCompactionPlanner automaticCompactionPlanner =
      new AutomaticCompactionPlanner();
  private final ThreadLifecycleCoordinator coordinator;

  public ThreadProcessor(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker) {
    this(store, resolver, config, clock, scheduler, heartbeatWorker, null);
  }

  /** 注入 Tool outcome 的 durable history 物化端口（可为 null：ToolResult 含 Resource 引用时 fail-closed）。 */
  public ThreadProcessor(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.heartbeatWorker = Objects.requireNonNull(heartbeatWorker, "heartbeatWorker");
    this.modelOutcomeAppender = new ModelOutcomeAppender(toolResultHistoryMaterializer);
    this.coordinator = new ThreadLifecycleCoordinator();
  }

  /**
   * 处理一次 dispatcher 已 claim 的 THREAD Work（single-action 持久化 reducer）。
   *
   * <p>先用 Work-only 短事务验证 claim 当前真实 owned，再进 per-thread admission guard（同一 claim 重复 / 并发投递一律 LOST
   * no-op；不同新 token 抢占 guard）。随后恰执行一次持久化 action：单短事务分类并执行；若该 action 是 speculative plan 则事务外 resolve
   * + 第二事务 CAS 提交。action 在事务内完成当前 claim；下一 action 已确定时先 {@code requestWork(THREAD)} 再
   * complete。事务内最终所有权围栏（final fence） / CAS 丢失抛出的 {@link ClaimLostSignal} 在事务完整回滚后在此捕获并映射为
   * LOST_OWNERSHIP。
   */
  public ThreadProcessResult process(ClaimedWork claim) {
    Objects.requireNonNull(claim, "claim");
    if (claim.target().type() != WorkTargetType.THREAD) {
      throw new IllegalArgumentException(
          "ThreadProcessor requires a THREAD work claim, got " + claim.target());
    }
    UUID threadId = claim.target().id();
    String token = claim.leaseToken();
    if (!claimOwned(claim)) {
      return ThreadProcessResult.LOST_OWNERSHIP;
    }
    if (!admissionGuard.tryAdmit(threadId, token)) {
      return ThreadProcessResult.LOST_OWNERSHIP;
    }
    try {
      TurnPlan plan = step(claim);
      return plan == null ? ThreadProcessResult.COMPLETED : resolveAndCommit(claim, plan);
    } catch (ClaimLostSignal lost) {
      return ThreadProcessResult.LOST_OWNERSHIP;
    } finally {
      admissionGuard.release(threadId, token);
    }
  }

  /**
   * 单 action 短事务：锁 Session KEY SHARE -&gt; Thread -&gt;（Commands -&gt; Model -&gt; Tool）-&gt;
   * Work，按固定优先级分类并恰执行一个 durable action。返回 null 表示 action 已在该事务完成 claim；非 null 表示构造了 speculative
   * plan，由调用方在事务外 resolve 后第二事务提交（第二事务同样完成 claim）。
   */
  private TurnPlan step(ClaimedWork claim) {
    return store.transaction(tx -> stepTx(tx, claim));
  }

  private TurnPlan stepTx(HarnessStore.Transaction tx, ClaimedWork claim) {
    Instant now = clock.instant();
    ThreadState thread = lockThreadWithSession(tx, claim.target().id());
    if (thread == null) {
      throw new ClaimLostSignal();
    }
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isPresent()
        && openTurn.get().payload() instanceof TurnStartPayload startPayload
        && startPayload.reason() == TurnStartReason.COMPACTION
        && startPayload.ownerThreadId().equals(thread.id())
        && startPayload.compaction() != null
        && startPayload.compaction().joinInvocationId() != null) {
      settleOpenCompactionTurn(
          tx, claim, thread, path, startPayload.compaction(), openTurn.get(), now);
      return null;
    }
    // 安全边界先应用全部 standalone 分支设置并追加 SETTINGS 快照；CONTRIBUTOR_STATE 留给普通输入 turn。
    // STOPPED 线程只结算设置，不恢复执行；存在 open turn 时保留冻结请求。
    if (path.openTurnStart().isEmpty() && applyPendingSettings(tx, claim, thread, path, now)) {
      return null;
    }
    // STOPPED 是持久的执行控制：停止后只固化通知，绝不自动启动模型；只有显式新人工/可信输入在 accept 阶段恢复
    // RUNNABLE 后才会再次进入这里。残留 claim 在这里完成 fencing，不产生任何 durable mutation。
    if (thread.executionControl().isStopped()) {
      completeClaim(tx, claim, now);
      return null;
    }
    // 唯一的 live/historical 适用性来源：不变量被破坏的形状由分类器以 ISE 拒绝，绝不降级为业务上下文。
    ThreadContext context = threadContextProbe.probe(tx, thread, path);
    return switch (context) {
      case ThreadContext.ModelTerminalPending pending -> {
        // 锁序 Thread -> Commands -> Model：判断 pre-existing queued message 必须在 lock Model 前加载。
        boolean hasQueuedMessage =
            ThreadInputDemand.hasQueuedDemand(tx.loadQueuedCommands(thread.id()));
        ModelInvocation locked = tx.lockModelInvocation(pending.model().id()).orElse(null);
        if (locked == null) {
          throw new ClaimLostSignal();
        }
        applyModel(tx, claim, thread, path, locked, now, hasQueuedMessage);
        yield null;
      }
      case ThreadContext.ModelActive ignored -> {
        completeClaim(tx, claim, now);
        yield null;
      }
      case ThreadContext.ToolTerminalPending pending -> {
        // 锁序 Thread -> Model -> Tool：Tool batch 固定 self-wake 下一 claim 的 continuation，不消费任何
        // Command，因此无需加载 Command 快照（不为形式锁序锁无关行）。
        ModelInvocation locked = tx.lockModelInvocation(pending.model().id()).orElse(null);
        if (locked == null) {
          throw new ClaimLostSignal();
        }
        List<ToolInvocation> lockedSiblings =
            tx.lockToolInvocationsByAssistantEntryId(pending.assistant().id());
        applyToolBatch(
            tx,
            claim,
            thread,
            path,
            locked,
            pending.assistant(),
            pending.calls(),
            lockedSiblings,
            now);
        yield null;
      }
      case ThreadContext.ToolActive ignored -> {
        completeClaim(tx, claim, now);
        yield null;
      }
      case ThreadContext.ContinuationDue ignored -> {
        // owned HISTORY 机械续作仍由 compactionPreparation 保持最高优先级；普通 continuation
        // 在越过 soft threshold 时先压缩，成功后再由 durable continueModel obligation 恢复。
        CompactionPreparation preparation =
            !isInCompactionChildTree(tx, thread)
                ? automaticCompactionPlanner.plan(
                    thread, path, config.compactionProvider().compactionConfig(), true)
                : null;
        if (preparation != null) {
          yield planStep(tx, claim, thread, path, TurnStartReason.COMPACTION, preparation, now);
        }
        // 压缩判断完成后，已关闭 turn 是安全边界：有序消费完整输入快照，
        // 让预算提醒、用户 steering 与 child completion 不必等自动续作彻底停止。
        if (ThreadInputDemand.hasInputDemand(tx, thread)) {
          yield planStep(tx, claim, thread, path, TurnStartReason.INPUT, now);
        }
        yield planStep(tx, claim, thread, path, TurnStartReason.CONTINUATION, now);
      }
      case ThreadContext.IdleOrHistorical ignored -> {
        // 已物化但位于输入水位之后的系统通知仍需普通输入处理：需求只按本 Thread 自己的 Command 判定（fork 不继承源邮箱，
        // 压缩改写 head 也不会丢失该需求）。
        boolean userDemand = ThreadInputDemand.hasInputDemand(tx, thread);
        // owned HISTORY / fallback / hard overflow 优先；idle soft threshold 只有真实 user demand 存在时才启动。
        CompactionPreparation preparation =
            !isInCompactionChildTree(tx, thread)
                ? automaticCompactionPlanner.plan(
                    thread, path, config.compactionProvider().compactionConfig(), userDemand)
                : null;
        if (preparation != null) {
          yield planStep(tx, claim, thread, path, TurnStartReason.COMPACTION, preparation, now);
        }
        if (userDemand) {
          yield planStep(tx, claim, thread, path, TurnStartReason.INPUT, now);
        }
        completeClaim(tx, claim, now);
        yield null;
      }
    };
  }

  /** blocker / idle 完成：final fence 后 completeWork；fence 失败抛内部信号回滚（零 mutation 的 LOST）。 */
  private static void completeClaim(HarnessStore.Transaction tx, ClaimedWork claim, Instant now) {
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.completeWork(claim, now);
  }

  /**
   * 安全边界上应用 queued 的全部 standalone 设置（SET_AGENT / SET_MODEL / SET_ENVIRONMENT）：从整段 queued snapshot
   * 按原序 提取这些命令，归约成完整 {@link BranchSettings} 快照并 append 一个 {@link SettingsPayload} Entry，标记命令
   * applied（指向该 SETTINGS Entry）后推进 head。绝不打开 / 关闭 Turn、绝不创建 ModelInvocation，也绝不 resume STOPPED
   * Thread——只有显式新输入才会 恢复 RUNNABLE。
   *
   * <p>从整段 snapshot 提取而非前导前缀：{@code [USER, SET_MODEL]} 这类交错必须让设置先于任何 input / 压缩生效，同时保留设置内部
   * 相对顺序；其它输入仍 queued 且输入水位不前进，随后的 INPUT / CONTINUATION 按原序消费，绝不跳过尚未处理的输入。
   *
   * <p>SET_CONTRIBUTOR_STATE 不是 standalone 设置：它只在本 Thread 的普通 turn 内作为有序 CUSTOM state Entry 物化，因此留给
   * 后续 turn，绝不在回合之间越界追加。
   *
   * <p>返回 false 表示没有可应用的设置，调用方继续正常分类。
   */
  private boolean applyPendingSettings(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      Instant now) {
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    List<ThreadCommand> settings = new ArrayList<>();
    for (ThreadCommand command : queued) {
      if (isStandaloneSetting(command.type())) {
        settings.add(command);
      }
    }
    if (settings.isEmpty()) {
      return false;
    }
    Instant mutationNow =
        HarnessStoreTime.notBefore(now, thread.updatedAt(), path.head().createdAt());
    BranchSettings applied =
        settingsReducer.reduce(thread.id(), path.baseSettings(), settings, tx::nextId);
    UUID settingsEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            settingsEntryId,
            thread.sessionId(),
            path.head().id(),
            new SettingsPayload(applied, thread.id()),
            mutationNow));
    List<ThreadCommand> consumed = new ArrayList<>(settings.size());
    for (ThreadCommand command : settings) {
      consumed.add(command.markApplied(settingsEntryId));
    }
    tx.updateCommands(consumed);
    tx.updateThread(thread.advanceHead(settingsEntryId, mutationNow));
    // final fence 最后执行：损失抛内部信号整事务回滚，绝无带 mutation 的 LOST 提交。
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    // STOPPED 上只落 durable 设置，不唤醒：显式新输入才是恢复 RUNNABLE 的唯一入口。
    if (!thread.executionControl().isStopped()) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    }
    tx.completeWork(claim, now);
    return true;
  }

  /**
   * standalone 设置 = branch settings（SET_AGENT / SET_MODEL / SET_ENVIRONMENT）；SET_CONTRIBUTOR_STATE
   * 不属于此列。
   */
  private static boolean isStandaloneSetting(ThreadCommandType type) {
    return type.isSetting() && type != ThreadCommandType.SET_CONTRIBUTOR_STATE;
  }

  /**
   * Terminal Model 原子应用（Thread -&gt; Model -&gt; Tool -&gt; Work 锁序），单 action 恰好执行一次并完成 claim。
   *
   * <p>关闭 turn 的路径（COMPLETE 无 calls / CONTINUE / LENGTH 无 calls / FILTERED / terminal failure /
   * cancel / unknown / compaction 关闭结果）在同一事务追加 TURN_END 后执行严格物化校验（attach-then-delete，经 Store 的
   * {@link ModelAttemptMaterialization} 校验）并物理删除 ModelInvocation；只有 active Tool phase （SUCCEEDED 带
   * calls）保留 parent 并 attach resultEntryId。CONTINUE 以 COMPLETED + continueModel=true 关闭 turn，与 Tool
   * sibling batch 一致地机械请求 THREAD，续写由既有 durable continuation 机制在下一 claim 启动。
   *
   * <p>wake 决定：active Tool phase 只为 READY 槽位请求 TOOL Work（全部 immediate terminal 则请求 THREAD 让 batch 经
   * ToolTerminalPending 应用并反馈模型）；完全结束的 closed turn 在新 Entries 插入、Thread advanced 后，只在已有 queued user
   * demand、fallback/hard-overflow obligation 或 CONTINUE 的 continueModel obligation 已确定时请求
   * THREAD。soft threshold 没有新 demand 时保持 idle；新命令在事务后到达会自行 requestWork。
   */
  private void applyModel(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      Instant now,
      boolean hasQueuedMessage) {
    if (!model.status().isTerminal()
        || model.resultEntryId() != null
        || !thread.headEntryId().equals(model.requestHeadEntryId())) {
      // 决策与执行同事务，理论不可达；改为明确的不变量失败，绝不降级为循环重试。
      throw new IllegalStateException(
          "terminal model apply preconditions changed under the same transaction for model "
              + model.id());
    }
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            now, thread.updatedAt(), path.head().createdAt(), model.updatedAt());
    TurnStartPayload turnStart =
        (TurnStartPayload)
            path.openTurnStart()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "terminal model must belong to an open TURN_START"))
                .payload();
    ModelOutcomeAppender.Applied applied =
        modelOutcomeAppender.appendModel(tx, path, model, mutationNow);
    UUID head = applied.headEntryId();
    UUID finalAnswerEntryId = applied.finalAnswerEntryId();
    boolean terminal = applied.terminal();
    boolean continueModel = applied.continueModel();
    boolean toolPhase = applied.toolPhase();
    List<ToolInvocation> invocations = applied.toolInvocations();
    ThreadState advancedThread;
    boolean requestThread = false;
    if (toolPhase) {
      advancedThread = thread.advanceHead(head, mutationNow);
      tx.updateThread(advancedThread);
    } else {
      // 完全结束的 closed turn 只重建 queued demand / fallback / hard-overflow obligation；soft threshold
      // 无新 demand 时不 self-wake。CONTINUE 例外：必须机械请求 THREAD，下一 claim 才偿还 continueModel
      // obligation 并启动续写。
      advancedThread = thread.advanceHead(head, mutationNow);
      boolean compactionDue =
          automaticCompactionPlanner.plan(
                  advancedThread,
                  tx.loadEntryPath(head),
                  config.compactionProvider().compactionConfig(),
                  hasQueuedMessage)
              != null;
      requestThread = continueModel || hasQueuedMessage || compactionDue;
      tx.updateThread(advancedThread);
      if (continueModel) {
        coordinator.remindSoftBudgetIfDue(tx, advancedThread, tx.loadEntryPath(head), mutationNow);
      }
      if (terminal) {
        // 执行终止边界：首次最终回答 / 不可继续失败与 Join 冻结、父通知接受原子提交。
        ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
            tx, advancedThread, head, finalAnswerEntryId, mutationNow, false);
      }
    }
    // final fence 最后执行：损失抛内部信号，整事务回滚，绝无带 mutation 的 LOST 提交。
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    if (toolPhase) {
      List<ToolInvocation> ordered = new ArrayList<>(invocations);
      ordered.sort(Comparator.comparing(ToolInvocation::id, UuidOrder.COMPARATOR));
      boolean readyRequested = false;
      for (ToolInvocation invocation : ordered) {
        if (invocation.status() == ToolInvocationStatus.READY) {
          readyRequested = true;
          EnvironmentId environmentId =
              invocation.binding() == null ? null : invocation.binding().environmentId();
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, invocation.id()), now, environmentId);
        }
      }
      if (!readyRequested) {
        // 全部 immediate terminal（schema-invalid / unknown / truncated）：不请求 TOOL Work，自唤醒 THREAD
        // 让 batch 经下一 claim 的 ToolTerminalPending 应用并把错误反馈给模型。
        requestThread = true;
      }
    }
    if (requestThread) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    }
    tx.completeWork(claim, now);
  }

  /** 结算已 matched 的 COMPACTION Join 并推进父 Thread 历史；子执行失败则落 AssistantErrorPayload。 */
  private void settleOpenCompactionTurn(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      CompactionStart start,
      Entry openTurn,
      Instant now) {
    ThreadJoin join =
        tx.findJoin(start.joinInvocationId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "compaction join " + start.joinInvocationId() + " not found"));
    if (!join.matched()) {
      completeClaim(tx, claim, now);
      return;
    }
    ThreadJoinReceipt receipt =
        ThreadJoinProjector.INSTANCE
            .project(tx, join)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "matched compaction join produced no receipt: " + join.invocationId()));
    EntryPayload resultPayload;
    TurnEndOutcome outcome;
    boolean continueModel = false;
    if (receipt.outcome() != ThreadJoinOutcome.COMPLETED) {
      String errorMessage =
          receipt.error() != null ? receipt.error() : "compaction child thread failed";
      resultPayload =
          new AssistantErrorPayload(new AssistantError("COMPACTION_FAILED", errorMessage), null);
      outcome = TurnEndOutcome.FAILED;
    } else {
      String summaryText = receipt.report();
      AssistantMessageMetadata metadata = null;
      if (join.terminalEntryId() != null && join.finalAnswerEntryId() != null) {
        EntryPath childPath = tx.loadEntryPath(join.terminalEntryId());
        for (Entry e : childPath.entries()) {
          if (e.id().equals(join.finalAnswerEntryId())
              && e.payload() instanceof MessagePayload msg) {
            metadata = msg.assistantMetadata();
            break;
          }
        }
      }
      resultPayload =
          CompactionResultEvaluator.evaluate(
              path, start, summaryText, metadata, start.outputBudget());
      if (resultPayload instanceof CompactionPayload) {
        outcome = TurnEndOutcome.COMPLETED;
        continueModel =
            start.phase() == CompactionPhase.HISTORY
                || start.trigger() == CompactionTrigger.OVERFLOW
                || (start.trigger() == CompactionTrigger.THRESHOLD
                    && CompactionHistory.hasPendingOwnedContinuation(thread, path, openTurn.id()));
      } else {
        outcome = TurnEndOutcome.FAILED;
      }
    }
    Instant mutationNow =
        HarnessStoreTime.notBefore(now, thread.updatedAt(), path.head().createdAt());
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            resultEntryId, path.root().sessionId(), path.head().id(), resultPayload, mutationNow));
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            path.root().sessionId(),
            resultEntryId,
            new TurnEndPayload(
                openTurn.id(),
                outcome,
                continueModel,
                outcome == TurnEndOutcome.FAILED ? TurnEndReason.TURN_FAILED : null,
                null),
            mutationNow));
    ThreadState advanced = thread.advanceHead(turnEndId, mutationNow);
    tx.updateThread(advanced);
    // STOPPED 父 Thread 只固化压缩结果，不请求任何 Work、不续作：恢复仍由显式输入驱动（与已停止父的 task 回执交付一致）。
    boolean stopped = thread.executionControl().isStopped();
    boolean hasQueuedDemand = ThreadInputDemand.hasQueuedDemand(tx.loadQueuedCommands(thread.id()));
    boolean compactionDue =
        !stopped
            && !isInCompactionChildTree(tx, thread)
            && automaticCompactionPlanner.plan(
                    advanced,
                    tx.loadEntryPath(turnEndId),
                    config.compactionProvider().compactionConfig(),
                    hasQueuedDemand)
                != null;
    boolean mechanicalWake = !stopped && outcome == TurnEndOutcome.COMPLETED && continueModel;
    boolean requestThread = hasQueuedDemand && !stopped || compactionDue || mechanicalWake;
    if (mechanicalWake) {
      EntryPath updatedPath = tx.loadEntryPath(turnEndId);
      coordinator.remindSoftBudgetIfDue(tx, advanced, updatedPath, mutationNow);
    }
    if (outcome == TurnEndOutcome.FAILED && !requestThread) {
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, advanced, turnEndId, null, mutationNow, false);
    }
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    if (requestThread) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    }
    tx.completeWork(claim, now);
  }

  /**
   * 防递归：只有位于 COMPACTION 子执行树内（自身或任一祖先持有尚未结算的 COMPACTION Join）的 Thread 才禁止再规划压缩。父 Thread 自己运行的
   * subagent task 与其他未结算 Join 都不构成阻塞，也不引入任何持久化标志：判定只复用既有 Join purpose 事实。
   *
   * <p>包内可见以供判定本身有聚焦测试。
   */
  static boolean isInCompactionChildTree(HarnessStore.Transaction tx, ThreadState thread) {
    for (UUID ancestorThreadId : tx.findAncestorChain(thread.id())) {
      for (ThreadJoin join : tx.loadIncompleteJoins(ancestorThreadId)) {
        if (join.purpose() == JoinPurpose.COMPACTION) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Tool sibling 原子应用：全部 terminal 才执行，按 callIndex 通过统一 appender 追加 effects + ToolResult，再追加
   * COMPLETED TURN_END(continueModel=true)；同一事务删除全部 child ToolInvocation 与 parent
   * ModelInvocation并固定先请求 THREAD 再 complete，下一 claim 才做 continuation。数量 / callIndex 前缀 / ownership
   * / terminal 任一违反即抛错回滚；删除 parent 前先执行 与 {@link ModelAttemptMaterialization} 等价的最小严格校验（attached
   * Assistant/result 与已物化失败 attempt 前缀），绝不 绕过校验直接 delete。低序 mutation 完成后最后执行 claimed THREAD Work
   * fence。
   */
  private void applyToolBatch(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      Entry assistant,
      List<ToolCallMessageContent> calls,
      List<ToolInvocation> siblings,
      Instant now) {
    if (calls.size() != siblings.size()) {
      throw new IllegalStateException(
          "tool sibling count must match the assistant tool calls of entry " + assistant.id());
    }
    for (int i = 0; i < siblings.size(); i++) {
      if (siblings.get(i).callIndex() != i) {
        throw new IllegalStateException(
            "tool siblings must be a contiguous callIndex prefix of entry " + assistant.id());
      }
      ToolInvocation sibling = siblings.get(i);
      if (!sibling.modelInvocationId().equals(model.id()) || !sibling.status().isTerminal()) {
        // 锁内复查：任何不一致都是不变量违反，回滚（绝不部分 apply）。
        throw new IllegalStateException(
            "tool siblings changed under lock for assistant entry " + assistant.id());
      }
    }
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            now, thread.updatedAt(), path.head().createdAt(), model.updatedAt());
    for (ToolInvocation sibling : siblings) {
      mutationNow = HarnessStoreTime.notBefore(mutationNow, sibling.updatedAt());
    }
    ModelOutcomeAppender.Applied applied =
        modelOutcomeAppender.appendToolBatch(tx, path, model, siblings, mutationNow);
    UUID turnEndId = applied.headEntryId();
    ThreadState advanced = thread.advanceHead(turnEndId, mutationNow);
    tx.updateThread(advanced);
    EntryPath updatedPath = tx.loadEntryPath(turnEndId);
    coordinator.remindSoftBudgetIfDue(tx, advanced, updatedPath, mutationNow);
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    // batch 关闭 turn 固定产生 continueModel 义务：先 request THREAD 再 complete，下一 claim 才做 continuation。
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    tx.completeWork(claim, now);
  }

  /** 在步骤事务内构造 speculative plan；claim fence 与 lease margin 在构造前完成。 */
  private TurnPlan planStep(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      TurnStartReason reason,
      Instant now) {
    return planStep(tx, claim, thread, path, reason, null, now);
  }

  /** COMPACTION turn 的 plan：切分事实由调用方传入（reason COMPACTION 时非空）。 */
  private TurnPlan planStep(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      TurnStartReason reason,
      CompactionPreparation preparation,
      Instant now) {
    // 分类与构造同事务：classifier 已确认的 precondition 在此不可达，泄露即为不变量失败，fail closed 绝不循环重试。
    // 回合之间可追加已物化系统通知；续写义务锚点必须看到通知之前的 TURN_END。
    if (reason == TurnStartReason.CONTINUATION
        && (path.openTurnStart().isPresent()
            || !(path.headIgnoringTrailingControlEntries().payload() instanceof TurnEndPayload end
                && end.continueModel()))) {
      throw new IllegalStateException(
          "continuation preconditions changed under the same transaction for thread "
              + thread.id());
    }
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    Work claimed = tx.lockClaimedWork(claim, now).orElse(null);
    if (claimed == null) {
      throw new ClaimLostSignal();
    }
    // 近过期 claim 在 Resolver 首次 heartbeat 前可能过期：plan 事务内先确保完整 lease margin。
    ProcessorLeaseSupport.ensureLeaseMargin(tx, claim, config.leaseConfig(), now);
    Instant planNow = HarnessStoreTime.notBefore(now, thread.updatedAt(), path.head().createdAt());
    // 最小事实：INPUT 可以没有任何 queued 输入，只要本 Thread 有尚未接纳的已物化通知；这些通知已在历史里，不重复写 entry。
    boolean pendingNotificationInput =
        reason == TurnStartReason.INPUT
            && ThreadInputDemand.hasUnconsumedNotification(
                tx.loadCommandsByThread(thread.id()), thread.inputThroughSequence());
    return planBuilder.build(
        thread.id(),
        path,
        reason,
        queued,
        thread.nextCommandSequence() - 1,
        tx::nextId,
        planNow,
        preparation,
        pendingNotificationInput);
  }

  /**
   * 事务外解析 + 第二事务 CAS 提交：只有显式 typed {@link TurnResolveTransientException}（DB / 网络瞬断）按失败延迟
   * reschedule（零 durable mutation）；确定性 resolver 异常 / null 结果 / validator 契约失败都落成 durable
   * AssistantError + FAILED TURN_END 并结算 Join，绝不无限重排，也不把 raw cause 写入 durable。提交 CAS（source head /
   * cutoff 内 Command 快照 / claim）失败抛 {@link ClaimLostSignal} 由 {@link #process} 映射为 LOST。YOLO 变化不使
   * plan 失效：commit 以第二事务锁到的 Thread 当前 YOLO 为准。成功后本 claim 已消费，返回 COMPLETED。
   */
  private ThreadProcessResult resolveAndCommit(ClaimedWork claim, TurnPlan plan) {
    AtomicBoolean heartbeatLost = new AtomicBoolean();
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            store,
            scheduler,
            heartbeatWorker,
            config.leaseConfig(),
            clock,
            () -> heartbeatLost.set(true));
    if (!heartbeat.start(claim)) {
      log.warn("cannot schedule work lease heartbeat for {}; rescheduling", claim.target());
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ThreadProcessResult.RESCHEDULED
          : ThreadProcessResult.LOST_OWNERSHIP;
    }
    TurnResolver.Result result;
    try {
      result = resolver.resolve(plan.threadId(), plan.candidatePath(), plan.preparation());
    } catch (TurnResolveTransientException transientFailure) {
      log.warn(
          "turn resolver infrastructure unavailable for thread {}; rescheduling its work",
          plan.threadId(),
          transientFailure);
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ThreadProcessResult.RESCHEDULED
          : ThreadProcessResult.LOST_OWNERSHIP;
    } catch (RuntimeException deterministicFailure) {
      // 确定性 resolver 失败（含编程 / 契约错误）：落 durable FAILED 并结算 Join，绝不无限 reschedule。
      log.warn(
          "turn resolver failed deterministically for thread {}; failing the turn",
          plan.threadId(),
          deterministicFailure);
      return commitDeterministicFailure(claim, plan);
    } finally {
      heartbeat.stop();
    }
    if (heartbeatLost.get()) {
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ThreadProcessResult.RESCHEDULED
          : ThreadProcessResult.LOST_OWNERSHIP;
    }
    if (result == null) {
      // null 违反 Resolver 契约：确定性失败，落 durable FAILED 而非无限重排。
      log.warn("turn resolver returned null for thread {}; failing the turn", plan.threadId());
      return commitDeterministicFailure(claim, plan);
    }
    if (result instanceof TurnResolver.Resolved resolved) {
      try {
        // Harness 边界校验：不一致即 Resolver 契约错误，此刻零 durable mutation，转为 typed FAILED 而非抛异常。
        ResolvedRequestValidator.validate(plan.candidatePath(), resolved);
      } catch (RuntimeException contractFailure) {
        log.warn(
            "resolved request violated the harness contract for thread {}; failing the turn",
            plan.threadId(),
            contractFailure);
        return commitDeterministicFailure(claim, plan);
      }
    }
    commit(claim, plan, result);
    return ThreadProcessResult.COMPLETED;
  }

  /**
   * 把确定性 resolver 失败落成 durable AssistantError + FAILED TURN_END 并结算 Join：复用 {@link
   * TurnResolver.Rejected} 的 提交流程，durable 文本稳定且绝不泄露 raw cause。若该轮有 deferred user input，commit 会重新请求
   * Thread 处理它。
   */
  private ThreadProcessResult commitDeterministicFailure(ClaimedWork claim, TurnPlan plan) {
    commit(claim, plan, new TurnResolver.Rejected(DETERMINISTIC_FAILURE));
    return ThreadProcessResult.COMPLETED;
  }

  /**
   * 在第二事务中基于重新锁定的 Thread 重验 head、命令快照与 Claim，按 Session KEY SHARE -&gt; Thread -&gt; Commands -&gt;
   * Model -&gt; Work 锁序原子提交；最终 Claim 围栏失败会回滚全部变更。
   */
  private void commit(ClaimedWork claim, TurnPlan plan, TurnResolver.Result result) {
    store.transaction(
        tx -> {
          commitTx(tx, claim, plan, result);
          return null;
        });
  }

  private void commitTx(
      HarnessStore.Transaction tx, ClaimedWork claim, TurnPlan plan, TurnResolver.Result result) {
    Instant now = clock.instant();
    ThreadState thread = lockThreadWithSession(tx, plan.threadId());
    if (thread == null) {
      throw new ClaimLostSignal();
    }
    if (!thread.headEntryId().equals(plan.sourceHeadEntryId())) {
      throw new ClaimLostSignal();
    }
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    if (!snapshotMatches(plan, queued)) {
      throw new ClaimLostSignal();
    }
    Instant mutationNow = HarnessStoreTime.notBefore(now, thread.updatedAt());
    for (Entry entry : plan.candidateEntries()) {
      mutationNow = HarnessStoreTime.notBefore(mutationNow, entry.createdAt());
    }
    Integer contextWindow = null;
    Integer maxOutputTokens = null;
    CompactionStart compactionStart = null;
    if (result instanceof TurnResolver.Resolved resolved) {
      contextWindow = resolved.contextWindow();
      maxOutputTokens = resolved.maxOutputTokens();
    } else if (result instanceof TurnResolver.CompactionResolved compactionResolved) {
      contextWindow = compactionResolved.contextWindow();
      maxOutputTokens = compactionResolved.maxOutputTokens();
      CompactionChildStarter.CompactionChild child =
          CompactionChildStarter.start(
              tx,
              thread,
              plan.candidatePath(),
              compactionResolved,
              plan.preparation(),
              mutationNow);
      compactionStart = child.frozenStart();
    }
    // 低序 mutation（Entries / Commands / Thread / ModelInvocation）先完成。
    for (Entry entry : plan.candidateEntries()) {
      tx.insertEntry(
          withCreatedAt(
              withResolvedTurnStart(entry, plan, contextWindow, maxOutputTokens, compactionStart),
              mutationNow));
    }
    List<ThreadCommand> consumed = new ArrayList<>(plan.consumedCommands().size());
    for (ThreadCommand command : plan.consumedCommands()) {
      consumed.add(command.markApplied(plan.appliedEntryId(command.sequence())));
    }
    tx.updateCommands(consumed);
    if (result instanceof TurnResolver.Resolved resolved) {
      ThreadState advanced =
          thread.advanceHeadAndInputThroughSequence(
              plan.candidateHeadEntryId(), nextWatermark(plan, thread), mutationNow);
      tx.updateThread(advanced);
      UUID invocationId = tx.nextId();
      tx.insertModelInvocation(
          new ModelInvocation(
              invocationId,
              thread.id(),
              plan.turnStartEntryId(),
              plan.candidateHeadEntryId(),
              resolved.spec(),
              ModelInvocationStatus.READY,
              0,
              null,
              null,
              null,
              null,
              List.of(),
              mutationNow,
              mutationNow));
      if (tx.lockClaimedWork(claim, now).isEmpty()) {
        throw new ClaimLostSignal();
      }
      tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId), now);
      tx.completeWork(claim, now);
    } else if (result instanceof TurnResolver.CompactionResolved) {
      ThreadState advanced =
          thread.advanceHeadAndInputThroughSequence(
              plan.candidateHeadEntryId(), nextWatermark(plan, thread), mutationNow);
      tx.updateThread(advanced);
      if (tx.lockClaimedWork(claim, now).isEmpty()) {
        throw new ClaimLostSignal();
      }
      tx.completeWork(claim, now);
    } else {
      TurnResolver.Rejected rejected = (TurnResolver.Rejected) result;
      UUID errorEntryId = tx.nextId();
      tx.insertEntry(
          new Entry(
              errorEntryId,
              plan.sessionId(),
              plan.candidateHeadEntryId(),
              new AssistantErrorPayload(rejected.error(), null),
              mutationNow));
      UUID turnEndId = tx.nextId();
      tx.insertEntry(
          new Entry(
              turnEndId,
              plan.sessionId(),
              errorEntryId,
              new TurnEndPayload(
                  plan.turnStartEntryId(),
                  TurnEndOutcome.FAILED,
                  false,
                  TurnEndReason.TURN_FAILED,
                  null),
              mutationNow));
      // 确定性 Rejected 失败绝不立即 self-wake 同一自动压缩：失败 turn 已落 durable，马上重排可能再次规划同一压缩而空转。
      // 只保留合法 queued user 需求——它会在下一 claim 作为 INPUT 正常处理（或同样落 durable 失败）；新的自动压缩只由后续真实
      // 输入 / 唤醒重新驱动。
      boolean requestThread = plan.hasDeferredUserMessages();
      ThreadState advanced =
          thread.advanceHeadAndInputThroughSequence(
              turnEndId, nextWatermark(plan, thread), mutationNow);
      tx.updateThread(advanced);
      // Rejected 是源输入的不可继续失败：执行终止边界结算本次 Join。
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, advanced, turnEndId, null, mutationNow, false);
      if (tx.lockClaimedWork(claim, now).isEmpty()) {
        throw new ClaimLostSignal();
      }
      // rejected：仅在仍有合法 user 需求时先请求 THREAD 再 complete。
      if (requestThread) {
        tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
      }
      tx.completeWork(claim, now);
    }
  }

  /**
   * 先获取执行树事务级锁并复核祖先链，再以不可变 Thread 快照定位父 Session，按 Session KEY SHARE -&gt; Thread FOR UPDATE 复核。
   *
   * <p>在任何 Session/Thread/Work 业务行锁之前先获取 Tree Advisory Lock，防止并发操作或深删除/停止产生逆序或竞态。
   */
  static ThreadState lockThreadWithSession(HarnessStore.Transaction tx, UUID threadId) {
    return ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, threadId);
  }

  /** 只有普通 INPUT 把输入水位推进到冻结 cutoff；CONTINUATION / COMPACTION 不推进，保留尚待接纳通知的原始尾部。 */
  private static long nextWatermark(TurnPlan plan, ThreadState thread) {
    if (plan.reason() == TurnStartReason.INPUT) {
      return plan.cutoffSequence();
    }
    return thread.inputThroughSequence();
  }

  /** cutoff 内 queued Command 与 planned 快照逐字段相等（id/thread/sequence/payload/client ID/state）。 */
  static boolean snapshotMatches(TurnPlan plan, List<ThreadCommand> queued) {
    Map<Long, ThreadCommand> plannedBySequence = new HashMap<>();
    for (ThreadCommand command : plan.plannedCommands()) {
      plannedBySequence.put(command.sequence(), command);
    }
    int withinCutoff = 0;
    for (ThreadCommand command : queued) {
      if (command.sequence() > plan.cutoffSequence()) {
        continue;
      }
      withinCutoff++;
      ThreadCommand planned = plannedBySequence.get(command.sequence());
      if (planned == null
          || !planned.threadId().equals(command.threadId())
          || !planned.payload().equals(command.payload())
          || !planned.idempotencyKey().equals(command.idempotencyKey())
          || planned.state() != command.state()) {
        return false;
      }
    }
    return withinCutoff == plan.plannedCommands().size();
  }

  static Entry withCreatedAt(Entry entry, Instant createdAt) {
    return new Entry(
        entry.id(), entry.sessionId(), entry.parentEntryId(), entry.payload(), createdAt);
  }

  /**
   * 第二阶段 commit 在插入前补齐 Resolver 成功时的 contextWindow/maxOutputTokens/compactionStart；rejected 保持
   * null。
   */
  static Entry withResolvedTurnStart(
      Entry entry,
      TurnPlan plan,
      Integer contextWindow,
      Integer maxOutputTokens,
      CompactionStart compactionStart) {
    if (!entry.id().equals(plan.turnStartEntryId())
        || !(entry.payload() instanceof TurnStartPayload start)) {
      return entry;
    }
    return new Entry(
        entry.id(),
        entry.sessionId(),
        entry.parentEntryId(),
        new TurnStartPayload(
            start.reason(),
            start.settings(),
            start.ownerThreadId(),
            contextWindow,
            maxOutputTokens,
            compactionStart != null ? compactionStart : start.compaction()),
        entry.createdAt());
  }

  /**
   * 同一 Assistant 的 sibling 都读取相同冻结 branch。若某 state key 已出现 WRITE，后续 READ/WRITE 必然读取陈旧快照， 因而在
   * dispatch 前确定性拒绝；READ 后 WRITE 与不同 key 保持并发。
   */
  private static ToolInvocationError siblingStateConflict(
      ContributorBinding contributor, Map<ContributorStateKey, ContributorStateAccessMode> seen) {
    if (contributor == null || contributor.stateAccesses().isEmpty()) {
      return null;
    }
    for (ContributorStateAccess access : contributor.stateAccesses()) {
      ContributorStateKey key =
          new ContributorStateKey(contributor.contributorId(), access.customType());
      if (seen.get(key) == ContributorStateAccessMode.WRITE) {
        return new ToolInvocationError(
            "SIBLING_STATE_CONFLICT",
            "A previous sibling tool writes contributor state "
                + key
                + "; call this tool in the next model turn.");
      }
    }
    for (ContributorStateAccess access : contributor.stateAccesses()) {
      ContributorStateKey key =
          new ContributorStateKey(contributor.contributorId(), access.customType());
      if (access.mode() == ContributorStateAccessMode.WRITE) {
        seen.put(key, ContributorStateAccessMode.WRITE);
      } else {
        seen.putIfAbsent(key, ContributorStateAccessMode.READ);
      }
    }
    return null;
  }

  private record ContributorStateKey(String contributorId, String customType) {
    @Override
    public String toString() {
      return contributorId + ":" + customType;
    }
  }

  /** Work-only 前置校验：仅锁 Work 行验证 claim 当前真实 owned。 */
  private boolean claimOwned(ClaimedWork claim) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(store.transaction(tx -> !tx.lockClaimedWork(claim, now).isEmpty()));
  }

  /** 原子 reschedule：同一事务内校验 claim 仍 owned，失败表示 lost。 */
  private boolean rescheduleIfOwned(ClaimedWork claim, Duration delay) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              if (tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              tx.rescheduleWork(claim, now, delay);
              return true;
            }));
  }

  /**
   * 内部回滚信号：final claim fence 或提交 CAS 丢失时抛出，使当前事务完整回滚（零 durable mutation），再由 {@link #process} 捕获并映射为
   * LOST_OWNERSHIP。除 {@link #process} 外不得被捕获。
   */
  private static final class ClaimLostSignal extends RuntimeException {
    private ClaimLostSignal() {
      super("claimed work lost at final fence", null, false, false);
    }
  }
}
