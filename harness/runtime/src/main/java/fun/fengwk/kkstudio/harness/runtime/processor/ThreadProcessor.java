package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.ThreadInputDemand;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionChildSpawn;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionResultEvaluator;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
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
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinProjector;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ResolvedRequestValidator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestReducer;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
 * <p>Turn 启动在单个 store transaction 内完成：按 Session KEY SHARE -&gt; Thread 顺序加锁、读取 queued Command 快照与
 * cutoff、校验 claim、分配 candidate Entry ID 并构造完整合法 candidate EntryPath（不写任何持久化状态），随后在同一事务内同步调用 {@link
 * TurnResolver}。Resolved 请求先经 {@link ResolvedRequestValidator} 按 candidate branch 事实（route / model
 * / variant / tools / compaction）做机械一致性校验，再一次性原子提交 TURN_START + Message + Command markers + Thread
 * 更新 + ModelInvocation/MODEL Work（resolved）或 AssistantError + FAILED TURN_END（rejected）。因此不存在事务外
 * resolve、二次 head/Command CAS 或解析期 heartbeat：candidate 只在解析完成后写入同一事务，要么全部提交、要么全部回滚。
 *
 * <p>只有显式 typed {@link TurnResolveTransientException}（DB / 网络瞬断）会使当前事务完整回滚，随后由 {@link #process}
 * 在事务边界外按单一正失败延迟 reschedule；确定性 resolver 异常 / null / validator 契约失败都落成 durable AssistantError +
 * FAILED TURN_END。任何 claim 损失一律抛内部 {@link ClaimLostSignal} 使事务完整回滚（零部分写入）， 由 {@link #process} 映射为
 * LOST_OWNERSHIP。duplicate / stale THREAD claim 是 no-op。
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
 * queued user demand、HISTORY / OVERFLOW obligation、hard overflow 或 CONTINUE 的 continueModel
 * obligation 已确定时请求 THREAD；完全结束的 idle run 不因 soft threshold 自唤醒。失败 / 停止 / complete COMPACTION 由
 * planner 判定不 spin。CONTINUE 与 Tool sibling batch 同构： 固定先请求 THREAD 再 complete，下一 claim 才由 durable
 * continuation 启动续写；Tool sibling batch 追加 outcome 后追加 continueModel=true TURN_END 并同事务删除
 * children+parent。resolve commit 的 resolved 只请求 MODEL Work，绝不因 deferred messages 制造无意义 THREAD claim
 * （terminal apply 会按 queued 快照重建 wake）；rejected 只在仍保留合法 deferred messages 时先请求 THREAD 再
 * complete，绝不立即重排同一压缩。
 *
 * <p>压缩触发正交：soft threshold 在尚未完成的 continuation 边界或下一条真实 user demand 到达时门控；hard overflow 立即压缩并只恢复一次。
 * 所有 trigger 共用 MODEL Work 与 deterministic no-gain，且统一在隔离的 child Runtime Thread 内执行（父 durable
 * wait、无父 ModelInvocation）；切分先 HISTORY 再以 continueModel obligation 机械启动 TURN_PREFIX。压缩消费零 queued
 * Command，另一 Thread 拥有的共享历史 turn 不压缩，COMPACTION 子执行树内绝不再次规划压缩。
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
  private final ModelOutcomeAppender modelOutcomeAppender;
  private final TurnPlanBuilder planBuilder = new TurnPlanBuilder();
  private final CommandHarvestReducer settingsReducer = new CommandHarvestReducer();
  private final ClaimAdmissionGuard admissionGuard = new ClaimAdmissionGuard();
  private final ThreadContextProbe threadContextProbe = new ThreadContextProbe();
  private final AutomaticCompactionPlanner automaticCompactionPlanner =
      new AutomaticCompactionPlanner();
  private final ThreadLifecycleCoordinator coordinator;

  public ThreadProcessor(HarnessStore store, TurnResolver resolver, ThreadProcessorConfig config) {
    this(store, resolver, config, null);
  }

  /** 注入 Tool outcome 的 durable history 物化端口（可为 null：ToolResult 含 Resource 引用时 fail-closed）。 */
  public ThreadProcessor(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.config = Objects.requireNonNull(config, "config");
    this.modelOutcomeAppender = new ModelOutcomeAppender(toolResultHistoryMaterializer);
    this.coordinator = new ThreadLifecycleCoordinator();
  }

  /**
   * 处理一次 dispatcher 已 claim 的 THREAD Work（single-action 持久化 reducer）。
   *
   * <p>先用 Work-only 短事务验证 claim 当前真实 owned，再进 per-thread admission guard（同一 claim 重复 / 并发投递一律 LOST
   * no-op；不同新 token 抢占 guard）。随后恰执行一次持久化 action：分类、构造 candidate turn plan、同事务 resolve 与提交都在同一个
   * store transaction 内完成。action 在事务内完成当前 claim；下一 action 已确定时先 {@code requestWork(THREAD)} 再
   * complete。事务内最终所有权围栏（final fence）失败抛出的 {@link ClaimLostSignal} 在事务完整回滚后在此捕获并映射为
   * LOST_OWNERSHIP；显式 typed {@link TurnResolveTransientException} 同样在事务回滚后按既有语义 reschedule。
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
      step(claim);
      return ThreadProcessResult.COMPLETED;
    } catch (ClaimLostSignal lost) {
      return ThreadProcessResult.LOST_OWNERSHIP;
    } catch (TurnResolveTransientException transientFailure) {
      // 事务已因 typed transient 回滚，candidate 尚未落库；只有此显式类型允许在事务边界外受控重排。
      log.warn(
          "turn resolver infrastructure unavailable for thread {}; rescheduling its work",
          threadId,
          transientFailure);
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ThreadProcessResult.RESCHEDULED
          : ThreadProcessResult.LOST_OWNERSHIP;
    } finally {
      admissionGuard.release(threadId, token);
    }
  }

  /**
   * 单 action 短事务：锁 Session KEY SHARE -&gt; Thread -&gt;（Commands -&gt; Model -&gt; Tool）-&gt;
   * Work，按固定优先级分类并恰执行一个 durable action。Turn 分支在同一事务内构造 candidate、同步 resolve、校验并提交，不存在跨事务的 plan 返回。
   */
  private void step(ClaimedWork claim) {
    store.transaction(
        tx -> {
          stepTx(tx, claim);
          return null;
        });
  }

  private void stepTx(HarnessStore.Transaction tx, ClaimedWork claim) {
    Instant now = tx.now();
    ThreadState thread = lockThreadWithSession(tx, claim.target().id());
    if (thread == null) {
      throw new ClaimLostSignal();
    }
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isPresent() && openTurn.get().payload() instanceof TurnStartPayload startPayload) {
      UUID ticketId = ownerTicketId(thread, startPayload);
      if (ticketId != null) {
        settleOwnerTurn(tx, claim, thread, path, startPayload, ticketId, openTurn.get(), now);
        return;
      }
    }
    // 安全边界先应用全部 standalone 分支设置并追加 SETTINGS 快照；CONTRIBUTOR_STATE 留给普通输入 turn。
    // STOPPED 线程只结算设置，不恢复执行；存在 open turn 时保留冻结请求。
    if (path.openTurnStart().isEmpty() && applyPendingSettings(tx, claim, thread, path, now)) {
      return;
    }
    // STOPPED 是持久的执行控制：停止后只固化通知，绝不自动启动模型；只有显式新人工/可信输入在 accept 阶段恢复
    // RUNNABLE 后才会再次进入这里。残留 claim 在这里完成 fencing，不产生任何 durable mutation。
    if (thread.executionControl().isStopped()) {
      completeClaim(tx, claim, now);
      return;
    }
    // 唯一的 live/historical 适用性来源：不变量被破坏的形状由分类器以 ISE 拒绝，绝不降级为业务上下文。
    ThreadContext context = threadContextProbe.probe(tx, thread, path);
    switch (context) {
      case ThreadContext.ModelTerminalPending pending -> {
        // 锁序 Thread -> Commands -> Model：判断 pre-existing queued message 必须在 lock Model 前加载。
        boolean hasQueuedMessage =
            ThreadInputDemand.hasQueuedDemand(tx.loadQueuedCommands(thread.id()));
        ModelInvocation locked = tx.lockModelInvocation(pending.model().id()).orElse(null);
        if (locked == null) {
          throw new ClaimLostSignal();
        }
        applyModel(tx, claim, thread, path, locked, now, hasQueuedMessage);
      }
      case ThreadContext.ModelActive ignored -> completeClaim(tx, claim, now);
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
      }
      case ThreadContext.ToolActive ignored -> completeClaim(tx, claim, now);
      case ThreadContext.ContinuationDue ignored -> {
        // owned HISTORY 机械续作仍由 compactionPreparation 保持最高优先级；普通 continuation
        // 在越过 soft threshold 时先压缩，成功后再由 durable continueModel obligation 恢复。
        CompactionPreparation preparation =
            thread.constraints().allowAutoCompaction()
                ? automaticCompactionPlanner.plan(
                    thread, path, config.compactionProvider().compactionConfig(), true)
                : null;
        if (preparation != null) {
          planStep(tx, claim, thread, path, TurnStartReason.COMPACTION, preparation, now);
          return;
        }
        // 压缩判断完成后，已关闭 turn 是安全边界：有序消费完整输入快照，
        // 让预算提醒、用户 steering 与 child completion 不必等自动续作彻底停止。
        if (ThreadInputDemand.hasInputDemand(tx, thread)) {
          planStep(tx, claim, thread, path, TurnStartReason.INPUT, now);
          return;
        }
        planStep(tx, claim, thread, path, TurnStartReason.CONTINUATION, now);
      }
      case ThreadContext.IdleOrHistorical ignored -> {
        // 已物化但位于输入水位之后的系统通知仍需普通输入处理：需求只按本 Thread 自己的 Command 判定（fork 不继承源邮箱，
        // 压缩改写 head 也不会丢失该需求）。
        boolean userDemand = ThreadInputDemand.hasInputDemand(tx, thread);
        // owned HISTORY / fallback / hard overflow 优先；idle soft threshold 只有真实 user demand 存在时才启动。
        CompactionPreparation preparation =
            thread.constraints().allowAutoCompaction()
                ? automaticCompactionPlanner.plan(
                    thread, path, config.compactionProvider().compactionConfig(), userDemand)
                : null;
        if (preparation != null) {
          planStep(tx, claim, thread, path, TurnStartReason.COMPACTION, preparation, now);
          return;
        }
        if (userDemand) {
          planStep(tx, claim, thread, path, TurnStartReason.INPUT, now);
          return;
        }
        completeClaim(tx, claim, now);
      }
    }
  }

  /** blocker / idle 完成：final fence 后 completeWork；fence 失败抛内部信号回滚（零 mutation 的 LOST）。 */
  private static void completeClaim(HarnessStore.Transaction tx, ClaimedWork claim, Instant now) {
    if (tx.lockClaimedWork(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.completeWork(claim);
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
    BranchSettings applied =
        settingsReducer.reduce(thread.id(), path.baseSettings(), settings, tx::nextId);
    UUID settingsEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            settingsEntryId,
            thread.sessionId(),
            path.head().id(),
            new SettingsPayload(applied, thread.id()),
            now));
    List<ThreadCommand> consumed = new ArrayList<>(settings.size());
    for (ThreadCommand command : settings) {
      consumed.add(command.markApplied(settingsEntryId));
    }
    tx.updateCommands(consumed);
    tx.updateThread(thread.advanceHead(settingsEntryId, now));
    // final fence 最后执行：损失抛内部信号整事务回滚，绝无带 mutation 的 LOST 提交。
    if (tx.lockClaimedWork(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    // STOPPED 上只落 durable 设置，不唤醒：显式新输入才是恢复 RUNNABLE 的唯一入口。
    if (!thread.executionControl().isStopped()) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    tx.completeWork(claim);
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
    TurnStartPayload turnStart =
        (TurnStartPayload)
            path.openTurnStart()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "terminal model must belong to an open TURN_START"))
                .payload();
    ModelOutcomeAppender.Applied applied = modelOutcomeAppender.appendModel(tx, path, model, now);
    UUID head = applied.headEntryId();
    UUID finalAnswerEntryId = applied.finalAnswerEntryId();
    boolean terminal = applied.terminal();
    boolean continueModel = applied.continueModel();
    boolean toolPhase = applied.toolPhase();
    List<ToolInvocation> invocations = applied.toolInvocations();
    ThreadState advancedThread;
    boolean requestThread = false;
    if (toolPhase) {
      advancedThread = thread.advanceHead(head, now);
      tx.updateThread(advancedThread);
    } else {
      // 完全结束的 closed turn 只重建 queued demand / fallback / hard-overflow obligation；soft threshold
      // 无新 demand 时不 self-wake。CONTINUE 例外：必须机械请求 THREAD，下一 claim 才偿还 continueModel
      // obligation 并启动续写。
      advancedThread = thread.advanceHead(head, now);
      // hard overflow / fallback obligation 与 idle soft threshold 都必须遵守同一条祖先链 guard：位于 COMPACTION
      // 子执行树内的
      // Thread（含压缩子自身的超限失败回合）绝不再次规划压缩，从而不产生递归压缩子、也不原样重复超限请求。
      boolean compactionDue =
          advancedThread.constraints().allowAutoCompaction()
              && automaticCompactionPlanner.plan(
                      advancedThread,
                      tx.loadEntryPath(head),
                      config.compactionProvider().compactionConfig(),
                      hasQueuedMessage)
                  != null;
      requestThread = continueModel || hasQueuedMessage || compactionDue;
      tx.updateThread(advancedThread);
      if (continueModel) {
        coordinator.remindSoftBudgetIfDue(tx, advancedThread, tx.loadEntryPath(head), now);
      }
      if (terminal) {
        // 执行终止边界：首次最终回答 / 不可继续失败与 Join 冻结、父通知接受原子提交。
        ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
            tx, advancedThread, head, finalAnswerEntryId, now, false);
      }
    }
    // final fence 最后执行：损失抛内部信号，整事务回滚，绝无带 mutation 的 LOST 提交。
    if (tx.lockClaimedWork(claim).isEmpty()) {
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
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, invocation.id()), environmentId);
        }
      }
      if (!readyRequested) {
        // 全部 immediate terminal（schema-invalid / unknown / truncated）：不请求 TOOL Work，自唤醒 THREAD
        // 让 batch 经下一 claim 的 ToolTerminalPending 应用并把错误反馈给模型。
        requestThread = true;
      }
    }
    if (requestThread) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    tx.completeWork(claim);
  }

  /**
   * 开放 Turn 的结算票据 id：只有 owner 自身持有的 COMPACTION Turn 携带 {@link JoinDelivery#SETTLE_OWNER_TURN} 票据（由
   * {@link CompactionStart#joinInvocationId()} 冻结）；其它 Turn 返回 null。
   */
  private static UUID ownerTicketId(ThreadState thread, TurnStartPayload start) {
    if (start.reason() == TurnStartReason.COMPACTION
        && start.ownerThreadId().equals(thread.id())
        && start.compaction() != null) {
      return start.compaction().joinInvocationId();
    }
    return null;
  }

  /** SETTLE_OWNER_TURN 交付的处理：票据冻结结果后，owner 的开放 Turn 按其 reason 消费它；票据尚未冻结时只完成 claim 继续等待。 */
  private void settleOwnerTurn(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      TurnStartPayload start,
      UUID ticketId,
      Entry openTurn,
      Instant now) {
    ThreadJoin join =
        tx.findJoin(ticketId)
            .orElseThrow(
                () -> new IllegalStateException("owner ticket " + ticketId + " not found"));
    if (join.delivery() != JoinDelivery.SETTLE_OWNER_TURN) {
      throw new IllegalStateException("ticket " + ticketId + " does not settle the owner turn");
    }
    if (!join.matched()) {
      completeClaim(tx, claim, now);
      return;
    }
    switch (start.reason()) {
      case COMPACTION -> settleCompactionTurn(
          tx, claim, thread, path, start.compaction(), join, openTurn, now);
      default -> throw new IllegalStateException(
          "turn reason " + start.reason() + " has no owner-settled ticket");
    }
  }

  /** 压缩 Turn 的结算：评估冻结的摘要并推进父 Thread 历史；子执行失败则落 AssistantErrorPayload。 */
  private void settleCompactionTurn(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      CompactionStart start,
      ThreadJoin join,
      Entry openTurn,
      Instant now) {
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
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(resultEntryId, path.root().sessionId(), path.head().id(), resultPayload, now));
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
            now));
    ThreadState advanced = thread.advanceHead(turnEndId, now);
    tx.updateThread(advanced);
    // STOPPED 父 Thread 只固化压缩结果，不请求任何 Work、不续作：恢复仍由显式输入驱动（与已停止父的 task 回执交付一致）。
    boolean stopped = thread.executionControl().isStopped();
    boolean hasQueuedDemand = ThreadInputDemand.hasQueuedDemand(tx.loadQueuedCommands(thread.id()));
    boolean compactionDue =
        !stopped
            && thread.constraints().allowAutoCompaction()
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
      coordinator.remindSoftBudgetIfDue(tx, advanced, updatedPath, now);
    }
    if (outcome == TurnEndOutcome.FAILED && !requestThread) {
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, advanced, turnEndId, null, now, false);
    }
    if (tx.lockClaimedWork(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    if (requestThread) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    tx.completeWork(claim);
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
    ModelOutcomeAppender.Applied applied =
        modelOutcomeAppender.appendToolBatch(tx, path, model, siblings, now);
    UUID turnEndId = applied.headEntryId();
    ThreadState advanced = thread.advanceHead(turnEndId, now);
    tx.updateThread(advanced);
    EntryPath updatedPath = tx.loadEntryPath(turnEndId);
    coordinator.remindSoftBudgetIfDue(tx, advanced, updatedPath, now);
    if (tx.lockClaimedWork(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    // batch 关闭 turn 固定产生 continueModel 义务：先 request THREAD 再 complete，下一 claim 才做 continuation。
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    tx.completeWork(claim);
  }

  /** 在步骤事务内构造 candidate 并立即在同一事务内 resolve、校验、提交；不返回任何跨事务 plan。 */
  private void planStep(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      TurnStartReason reason,
      Instant now) {
    planStep(tx, claim, thread, path, reason, null, now);
  }

  /** COMPACTION turn 的 plan：切分事实由调用方传入（reason COMPACTION 时非空）。 */
  private void planStep(
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
    // claim 已在 process 入口用 Work-only 事务验证；本事务不再提前锁 Work（否则会在低序 Entry / ModelInvocation 之前
    // 取得高序 WORK 锁并破坏锁序）。最终 commit fence 在全部低序 mutation 后校验所有权，失败即整体回滚。
    // 最小事实：INPUT 可以没有任何 queued 输入，只要本 Thread 有尚未接纳的已物化通知；这些通知已在历史里，不重复写 entry。
    boolean pendingNotificationInput =
        reason == TurnStartReason.INPUT
            && ThreadInputDemand.hasUnconsumedNotification(
                tx.loadCommandsByThread(thread.id()), thread.inputThroughSequence());
    TurnPlan plan =
        planBuilder.build(
            thread.id(),
            path,
            reason,
            queued,
            thread.nextCommandSequence() - 1,
            tx::nextId,
            now,
            preparation,
            pendingNotificationInput);
    resolveAndCommitTx(tx, claim, thread, plan);
  }

  /**
   * 在调用方已锁定 Session / Thread 的同一事务内同步解析并原子提交：candidate Entry 只存在于内存，Resolver 直接消费其预分配
   * ID，不要求也未写入任何持久化 plan。只有显式 typed {@link TurnResolveTransientException} 向上抛出， 由 {@link #process}
   * 在事务回滚后受控重排（candidate 零写入）；确定性 resolver 异常 / null 结果 / validator 契约失败都转为 durable AssistantError
   * + FAILED TURN_END 并结算 Join，绝不无限重排，也不把 raw cause 写入 durable。
   */
  private void resolveAndCommitTx(
      HarnessStore.Transaction tx, ClaimedWork claim, ThreadState thread, TurnPlan plan) {
    TurnResolver.Result result;
    try {
      result = resolver.resolve(plan.threadId(), plan.candidatePath(), plan.preparation());
    } catch (TurnResolveTransientException transientFailure) {
      // 事务边界必须整体回滚；在 rollback-only 事务里继续写错误终态既不安全也不真实。
      throw transientFailure;
    } catch (RuntimeException deterministicFailure) {
      // 确定性 resolver 失败（含编程 / 契约错误）：落 durable FAILED 并结算 Join，绝不无限 reschedule。
      log.warn(
          "turn resolver failed deterministically for thread {}; failing the turn",
          plan.threadId(),
          deterministicFailure);
      result = new TurnResolver.Rejected(DETERMINISTIC_FAILURE);
    }
    if (result == null) {
      // null 违反 Resolver 契约：确定性失败，落 durable FAILED 而非无限重排。
      log.warn("turn resolver returned null for thread {}; failing the turn", plan.threadId());
      result = new TurnResolver.Rejected(DETERMINISTIC_FAILURE);
    } else if (result instanceof TurnResolver.Resolved resolved) {
      try {
        // Harness 边界校验：不一致即 Resolver 契约错误，此刻尚无 durable mutation，转为 FAILED 而非抛异常。
        ResolvedRequestValidator.validate(plan.candidatePath(), resolved);
      } catch (RuntimeException contractFailure) {
        log.warn(
            "resolved request violated the harness contract for thread {}; failing the turn",
            plan.threadId(),
            contractFailure);
        result = new TurnResolver.Rejected(DETERMINISTIC_FAILURE);
      }
    }
    commitTx(tx, claim, thread, plan, result);
  }

  /**
   * 在已锁定 Session / Thread / Claim 的当前事务中按低序 mutation 原子提交 TURN_START + Message + Command markers +
   * Thread 更新 + ModelInvocation/MODEL Work（resolved）或 AssistantError + FAILED TURN_END（rejected）。最终
   * Claim 围栏失败会回滚全部变更。
   */
  private void commitTx(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      TurnPlan plan,
      TurnResolver.Result result) {
    Instant now = tx.now();
    Integer contextWindow = null;
    Integer maxOutputTokens = null;
    CompactionStart compactionStart = null;
    ModelRequestSpec invocationSpec = null;
    if (result instanceof TurnResolver.Resolved resolved) {
      contextWindow = resolved.contextWindow();
      // Thread 冻结的创建约束（压缩子的输出预算、cache 禁用）作用于它的每一次请求（含工具 loop turn 与失败重试）：
      // TURN_START 冻结的 maxOutputTokens 必须等于实际 spec。
      ThreadConstraints constraints = thread.constraints();
      invocationSpec = applyConstraints(resolved.spec(), constraints);
      maxOutputTokens =
          constraints.maxOutputTokens() != null
              ? invocationSpec.outputTokens()
              : resolved.maxOutputTokens();
    } else if (result instanceof TurnResolver.CompactionResolved compactionResolved) {
      contextWindow = compactionResolved.contextWindow();
      maxOutputTokens = compactionResolved.maxOutputTokens();
      compactionStart =
          CompactionChildSpawn.start(tx, thread, compactionResolved, plan.preparation());
    }
    // 低序 mutation（Entries / Commands / Thread / ModelInvocation）先完成。
    for (Entry entry : plan.candidateEntries()) {
      tx.insertEntry(
          withCreatedAt(
              withResolvedTurnStart(
                  entry, plan, contextWindow, maxOutputTokens, compactionStart, invocationSpec),
              now));
    }
    List<ThreadCommand> consumed = new ArrayList<>(plan.consumedCommands().size());
    for (ThreadCommand command : plan.consumedCommands()) {
      consumed.add(command.markApplied(plan.appliedEntryId(command.sequence())));
    }
    tx.updateCommands(consumed);
    if (result instanceof TurnResolver.Resolved resolved) {
      ThreadState advanced =
          thread.advanceHeadAndInputThroughSequence(
              plan.candidateHeadEntryId(), nextWatermark(plan, thread), now);
      tx.updateThread(advanced);
      UUID invocationId = tx.nextId();
      tx.insertModelInvocation(
          new ModelInvocation(
              invocationId,
              thread.id(),
              plan.turnStartEntryId(),
              plan.candidateHeadEntryId(),
              invocationSpec,
              ModelInvocationStatus.READY,
              0,
              null,
              null,
              null,
              null,
              List.of(),
              now,
              now));
      if (tx.lockClaimedWork(claim).isEmpty()) {
        throw new ClaimLostSignal();
      }
      tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId));
      tx.completeWork(claim);
    } else if (result instanceof TurnResolver.CompactionResolved) {
      ThreadState advanced =
          thread.advanceHeadAndInputThroughSequence(
              plan.candidateHeadEntryId(), nextWatermark(plan, thread), now);
      tx.updateThread(advanced);
      if (tx.lockClaimedWork(claim).isEmpty()) {
        throw new ClaimLostSignal();
      }
      tx.completeWork(claim);
    } else {
      TurnResolver.Rejected rejected = (TurnResolver.Rejected) result;
      UUID errorEntryId = tx.nextId();
      tx.insertEntry(
          new Entry(
              errorEntryId,
              plan.sessionId(),
              plan.candidateHeadEntryId(),
              new AssistantErrorPayload(rejected.error(), null),
              now));
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
              now));
      // 确定性 Rejected 失败绝不立即 self-wake 同一自动压缩：失败 turn 已落 durable，马上重排可能再次规划同一压缩而空转。
      // 只保留合法 queued user 需求——它会在下一 claim 作为 INPUT 正常处理（或同样落 durable 失败）；新的自动压缩只由后续真实
      // 输入 / 唤醒重新驱动。
      boolean requestThread = plan.hasDeferredUserMessages();
      ThreadState advanced =
          thread.advanceHeadAndInputThroughSequence(turnEndId, nextWatermark(plan, thread), now);
      tx.updateThread(advanced);
      // Rejected 是源输入的不可继续失败：执行终止边界结算本次 Join。
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, advanced, turnEndId, null, now, false);
      if (tx.lockClaimedWork(claim).isEmpty()) {
        throw new ClaimLostSignal();
      }
      // rejected：仅在仍有合法 user 需求时先请求 THREAD 再 complete。
      if (requestThread) {
        tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
      }
      tx.completeWork(claim);
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

  static Entry withCreatedAt(Entry entry, Instant createdAt) {
    return new Entry(
        entry.id(), entry.sessionId(), entry.parentEntryId(), entry.payload(), createdAt);
  }

  /**
   * 把 Thread 的冻结约束作用于请求 spec：输出预算取 min 收紧，{@code promptCacheDisabled} 时把 cache 固定为 NONE；其余字段（模型、
   * variant、system instruction、tools、subagents）原样保留。
   */
  private static ModelRequestSpec applyConstraints(
      ModelRequestSpec spec, ThreadConstraints constraints) {
    if (constraints.maxOutputTokens() == null && !constraints.promptCacheDisabled()) {
      return spec;
    }
    int outputTokens =
        constraints.maxOutputTokens() == null
            ? spec.outputTokens()
            : Math.min(spec.outputTokens(), constraints.maxOutputTokens());
    return new ModelRequestSpec(
        spec.providerType(),
        spec.providerConnectionGenerationId(),
        spec.model(),
        spec.variant(),
        outputTokens,
        spec.systemInstruction(),
        spec.toolBindings(),
        spec.subagentBindings(),
        constraints.promptCacheDisabled() ? ProviderCacheControl.none() : spec.cacheControl());
  }

  /**
   * 第二阶段 commit 在插入前补齐 Resolver 成功时的 contextWindow/maxOutputTokens/compactionStart；rejected 保持
   * null。Resolver 改用 Agent 默认模型时（实际调用型号不同于 Thread 设置），把实际型号记为 fallbackModel。
   */
  static Entry withResolvedTurnStart(
      Entry entry,
      TurnPlan plan,
      Integer contextWindow,
      Integer maxOutputTokens,
      CompactionStart compactionStart,
      ModelRequestSpec resolvedSpec) {
    if (!entry.id().equals(plan.turnStartEntryId())
        || !(entry.payload() instanceof TurnStartPayload start)) {
      return entry;
    }
    ModelSelection executed =
        resolvedSpec == null
            ? null
            : new ModelSelection(
                resolvedSpec.model().providerName(),
                resolvedSpec.model().modelName(),
                resolvedSpec.variant().id());
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
            compactionStart != null ? compactionStart : start.compaction(),
            executed == null || executed.equals(start.settings().model()) ? null : executed),
        entry.createdAt());
  }

  /** Work-only 前置校验：仅锁 Work 行验证 claim 当前真实 owned。 */
  private boolean claimOwned(ClaimedWork claim) {
    return Boolean.TRUE.equals(store.transaction(tx -> !tx.lockClaimedWork(claim).isEmpty()));
  }

  /** 原子 reschedule：同一事务内校验 claim 仍 owned，失败表示 lost。 */
  private boolean rescheduleIfOwned(ClaimedWork claim, Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              if (tx.lockClaimedWork(claim).isEmpty()) {
                return false;
              }
              tx.rescheduleWork(claim, delay);
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
