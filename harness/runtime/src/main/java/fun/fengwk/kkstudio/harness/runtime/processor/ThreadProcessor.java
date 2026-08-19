package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryAssembler;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
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
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Target Thread processor：消费 dispatcher 已 claim 的 THREAD Work，每个 claim 恰好执行一个 durable action 的
 * single-action reducer（KISS），下一 action 一律由同事务 {@code requestWork} 驱动，绝不内部循环。
 *
 * <p>处理流程：先用纯 {@link ThreadContextClassifier} 把当前 Thread 分类为唯一的 live/historical 适用性上下文 （{@link
 * ThreadContext}，unlocked 读：Model 只按 (threadId, open TURN_START) 查找，Tool siblings 只在 Model 结果恰为当前
 * Assistant head 时加载），再按上下文恰执行一个动作：(a) MODEL_TERMINAL_PENDING —— head 恰为 basis 且未挂结果的 terminal
 * ModelInvocation 原子 apply；(b) TOOL_TERMINAL_PENDING —— 当前 head 恰为本 Thread ModelInvocation 产出 的
 * Assistant Entry、且其 Tool siblings 全部 terminal 时按 ordinal 原子 apply；(c) MODEL_ACTIVE / TOOL_ACTIVE
 * —— 当前 applicable invocation 非 terminal 时完成 claim；(d) CONTINUATION_DUE —— 无 open Turn 且 head 为
 * continueModel=true 的 TURN_END 时启动 continuation；(e) queued USER_MESSAGE/CUSTOM_MESSAGE 存在时启动 INPUT
 * Turn（IDLE_OR_HISTORICAL）；(f) 否则完成 claim。旧 / 历史 open Turn 只在启动 新 INPUT Turn 时被 normalization，绝不恢复
 * / 复用。不变量被破坏的形状由分类器以 ISE 拒绝，绝不降级为业务上下文。
 *
 * <p>Turn 启动采用 speculative plan：短事务锁 Thread、读取 queued Command 快照与 cutoff、校验 claim（并对近过期 lease 做
 * {@link ProcessorLeaseSupport#ensureLeaseMargin} 保证首次 Resolver heartbeat 前不会过期）、分配 candidate Entry
 * ID 并构造完整合法 candidate EntryPath（不写任何 durable 状态）；事务外调用 {@link TurnResolver}（期间由本地 {@link
 * WorkHeartbeat} 维持 lease）；第二短事务以 source head / cutoff 内 Command 精确快照 / claim ownership 做
 * CAS，一次性原子提交 TURN_START + Message + Command markers + Thread 更新 + ModelInvocation/MODEL
 * Work（resolved） 或 AssistantError + FAILED TURN_END（rejected）。Resolved 请求在提交前先经 {@link
 * ResolvedRequestValidator} 按 candidate branch 事实（route / model / variant / tools /
 * compaction）做机械一致性校验，不一致即抛错且零 durable mutation（绝不转 typed rejection）。任何 CAS / claim 损失一律抛内部 {@link
 * ClaimLostSignal} 使事务完整回滚（零部分 mutation），由 {@link #process} 映射为 LOST_OWNERSHIP；Resolver 异常 / null /
 * heartbeat 调度失败按单一正失败延迟 reschedule，绝不静默丢弃 Work。duplicate / stale THREAD claim 是 no-op。
 *
 * <p>锁序与 final fence：Model terminal apply / Tool sibling batch / resolve commit 都在同一事务内先完成全部低序
 * mutation（Thread -&gt; Commands -&gt; ModelInvocation -&gt; ToolInvocation siblings），claimed
 * THREAD Work 的最终 fence 最后执行；fence 失败抛出内部 {@link ClaimLostSignal} 使事务完整回滚，再由 {@link #process} 映射为
 * LOST_OWNERSHIP，绝不存在带 durable mutation 的 LOST 提交。commit 与 applyModel 在同层 Work 中按 (type, id) 升序请求（先
 * THREAD wake 再 MODEL / TOOL Work）。历史 / 非 applicable open Turn（head 不在 applicable 位置） 在分类阶段只使用
 * unlocked 读，绝不先锁 Model/Tool 再落到 Commands / INPUT normalization。
 *
 * <p>每次成功 action 都在同一事务 complete 当前 THREAD claim；下一 action 已确定时先 {@code requestWork(THREAD)} 再
 * complete，依赖 wakeVersion 保留新 wake。Model terminal apply 按 planner 决策落地：active Tool phase 仅为 READY
 * 槽位请求 TOOL Work（全部 immediate terminal 则请求 THREAD 让 batch 分下一 claim 经 ToolTerminalPending 应用）；
 * closed turn（COMPLETE 无 calls / LENGTH 无 calls / FILTERED / terminal failure / cancel / unknown /
 * compaction 关闭结果）在同一事务追加 TURN_END 与严格物化校验（attach-then-delete）并物理删除 ModelInvocation，且仅在已有 queued
 * message、成功 HISTORY / OVERFLOW compaction 需要延续、或闭合后用新 head path 判定立即出现 compaction action （如普通成功
 * turn 刚越过 threshold 自动自压）时请求 THREAD；失败 / 停止 / complete COMPACTION 由 planner 判定不 spin。 Tool sibling
 * batch 追加 outcome 后 追加 continueModel=true TURN_END、同事务删除 children+parent，固定先请求 THREAD 再
 * complete，下一 claim 才做 continuation。resolve commit 的 resolved 只请求 MODEL Work，绝不因 deferred messages
 * 制造无意义 THREAD claim （terminal apply 会按 queued 快照重建 wake）；rejected 在保留 deferred messages 或闭合即出现
 * compaction action 时先请求 THREAD 再 complete。
 *
 * <p>durable mutation 时间会抬升到事务内已锁定 Thread/path/Model/Tool 事实的时间下界；Work ownership、renew、
 * complete、request 与 reschedule 始终使用未抬升的本地 lease clock，避免未来 durable 时间改变 lease 语义。
 *
 * <p>自动压缩：无 open turn / continuation 待续且无 queued 输入时，按最新已关闭非压缩 turn 的 usage（providerTotalTokens
 * 优先，否则 categorizedTokens）与冻结 contextWindow 做阈值触发（{@code > max(0, contextWindow -
 * reserveTokens)}），或按 terminal OVERFLOW 错误触发 overflow 压缩；压缩 turn 复用同一 MODEL invocation + Work
 * 状态机，成功把 COMPACTION 结果 Entry 挂到 resultEntryId 后关闭（OVERFLOW 用 continueModel=true 让既有 CONTINUATION
 * 重试失败 turn，THRESHOLD 用 false）。切分 turn 先 HISTORY（incomplete payload）再机械启动独立 TURN_PREFIX 调用； 失败 / 停止
 * / 完成的压缩 turn 绝不立即再次压缩，压缩消费零 queued Command，另一 Thread 拥有的共享历史 turn 不压缩。
 */
@Slf4j
public final class ThreadProcessor {

  private final HarnessStore store;
  private final TurnResolver resolver;
  private final ThreadProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;
  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();
  private final TurnPlanBuilder planBuilder = new TurnPlanBuilder();
  private final ClaimAdmissionGuard admissionGuard = new ClaimAdmissionGuard();
  private final ThreadContextClassifier contextClassifier = new ThreadContextClassifier();
  private final ModelResponsePlanner responsePlanner = new ModelResponsePlanner();
  private final CompactionPlanner compactionPlanner;

  public ThreadProcessor(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this(store, resolver, config, clock, scheduler, null);
  }

  /** 注入 Tool outcome 的 durable history 物化端口（可为 null：ToolResult 含 Resource 引用时 fail-closed）。 */
  public ThreadProcessor(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.toolResultHistoryMaterializer = toolResultHistoryMaterializer;
    this.compactionPlanner = new CompactionPlanner(config.compaction());
  }

  /**
   * 处理一次 dispatcher 已 claim 的 THREAD Work（single-action durable reducer）。
   *
   * <p>先用 Work-only 短事务验证 claim 当前真实 owned，再进 per-thread admission guard（同一 claim 重复 / 并发投递一律 LOST
   * no-op；不同新 token 抢占 guard）。随后恰执行一次 durable action：单短事务分类并执行；若该 action 是 speculative plan 则事务外
   * resolve + 第二事务 CAS 提交。action 在事务内完成当前 claim；下一 action 已确定时先 {@code requestWork(THREAD)} 再
   * complete。事务内 final fence / CAS 丢失抛出的 {@link ClaimLostSignal} 在事务完整回滚后在此捕获并映射为 LOST_OWNERSHIP；
   * 不存在内部 run loop / step 循环 / Continue 分支。
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
   * 单 action 短事务：锁 Thread -&gt;（Commands -&gt; Model -&gt; Tool）-&gt; Work，按固定优先级分类并恰执行一个 durable
   * action。返回 null 表示 action 已在该事务完成 claim；非 null 表示构造了 speculative plan，由调用方在事务外 resolve 后第二事务
   * 提交（第二事务同样完成 claim）。
   */
  private TurnPlan step(ClaimedWork claim) {
    return store.transaction(tx -> stepTx(tx, claim));
  }

  private TurnPlan stepTx(HarnessStore.Transaction tx, ClaimedWork claim) {
    Instant now = clock.instant();
    ThreadState thread = tx.lockThread(claim.target().id()).orElse(null);
    if (thread == null) {
      throw new ClaimLostSignal();
    }
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    ModelInvocation model = null;
    List<ToolInvocation> siblings = List.of();
    var openTurn = path.openTurnStart();
    if (openTurn.isPresent()) {
      // unlocked 读：决策阶段不产生任何 Model/Tool 锁。model 只按 (threadId, open TURN_START) 精确查找；Tool
      // siblings 只在 model 结果恰为当前 head 且 head 为 ASSISTANT Message 时加载，否则保持空。
      model = tx.findModelInvocationByTurn(thread.id(), openTurn.get().id()).orElse(null);
      if (model != null
          && model.resultEntryId() != null
          && model.resultEntryId().equals(thread.headEntryId())
          && path.head().payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        siblings = tx.loadToolInvocationsByAssistantEntryId(path.head().id());
      }
    }
    // 唯一的 live/historical 适用性来源：不变量被破坏的形状由分类器以 ISE 拒绝，绝不降级为业务上下文。
    ThreadContext context = contextClassifier.classify(thread, path, model, siblings);
    return switch (context) {
      case ThreadContext.ModelTerminalPending pending -> {
        // 锁序 Thread -> Commands -> Model：判断 pre-existing queued message 必须在 lock Model 前加载。
        boolean hasQueuedMessage = hasQueuedMessage(tx.loadQueuedCommands(thread.id()));
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
        // continuation 优先：现有 continueModel 义务必须原样执行，阈值压缩绝不插队（否则会吞掉既有 continuation）。
        // OVERFLOW 失败的 turn 已关闭为 FAILED/idle，仍会通过 IdleOrHistorical 的 overflow 路径压缩。
        yield planStep(tx, claim, thread, path, TurnStartReason.CONTINUATION, now);
      }
      case ThreadContext.IdleOrHistorical ignored -> {
        // 压缩优先于输入：到期压缩先执行（消费零 Command），不能被已 queued 的用户消息绕过。
        CompactionPreparation preparation = compactionPreparation(thread, path);
        if (preparation != null) {
          yield planStep(tx, claim, thread, path, TurnStartReason.COMPACTION, preparation, now);
        }
        List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
        if (hasQueuedMessage(queued)) {
          yield planStep(tx, claim, thread, path, TurnStartReason.INPUT, now);
        }
        completeClaim(tx, claim, now);
        yield null;
      }
    };
  }

  /** queued 快照中是否存在待处理 message 命令（USER/CUSTOM）。 */
  private static boolean hasQueuedMessage(List<ThreadCommand> queued) {
    for (ThreadCommand command : queued) {
      if (command.type().isMessage()) {
        return true;
      }
    }
    return false;
  }

  /** blocker / idle 完成：final fence 后 completeWork；fence 失败抛内部信号回滚（零 mutation 的 LOST）。 */
  private static void completeClaim(HarnessStore.Transaction tx, ClaimedWork claim, Instant now) {
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.completeWork(claim, now);
  }

  /**
   * 计算当前 Thread 是否应当启动一次压缩 turn（纯读、无锁写）。
   *
   * <p>只基于路径上最新已关闭 turn 的 Entry 事实：该 turn 是 COMPACTION 时，只有其结果为 incomplete HISTORY payload 才机械启动
   * TURN_PREFIX（失败 / 停止 / 完成的压缩 turn 绝不立即再次压缩，避免 spin）。非压缩 turn 必须 {@code ownerThreadId} 等于当前
   * Thread（共享历史是 ownership barrier）；{@code contextWindow == null} 的 Resolver rejected turn 可跨越。最新
   * terminal OVERFLOW 错误优先触发 overflow 压缩；否则在最近一次 complete COMPACTION barrier 之后向前寻找本 Thread 最新成功
   * Assistant usage，超过 {@code max(0, contextWindow - reserveTokens)} 时阈值触发（providerTotalTokens 为 0
   * 时回退 categorizedTokens，全零不触发）。planner 无内容可摘要时返回 null。活跃 open turn 查找仍可使用当前 Invocation。
   */
  private CompactionPreparation compactionPreparation(ThreadState thread, EntryPath path) {
    CompactionConfig compaction = config.compaction();
    if (!compaction.enabled()) {
      return null;
    }
    ClosedTurn latestTurn = latestClosedTurn(path, path.entries().size());
    if (latestTurn == null) {
      return null;
    }
    Entry latestStart = latestTurn.start();
    TurnStartPayload latestStartPayload = (TurnStartPayload) latestStart.payload();
    if (latestStartPayload.reason() == TurnStartReason.COMPACTION) {
      // 机械延续：最新关闭的 COMPACTION turn 结果为 incomplete HISTORY payload 时启动第二次 TURN_PREFIX 调用。
      if (latestTurn.end().outcome() != TurnEndOutcome.COMPLETED) {
        return null;
      }
      CompactionPayload incomplete = compactionResultOfTurn(path, latestStart);
      if (incomplete == null || incomplete.phase() != CompactionPhase.HISTORY) {
        return null;
      }
      Integer contextWindow = latestStartPayload.contextWindow();
      if (contextWindow == null) {
        return null;
      }
      return compactionPlanner.prepareTurnPrefix(path, incomplete, contextWindow);
    }
    if (!thread.id().equals(latestStartPayload.ownerThreadId())) {
      // 最新已关闭 turn 属于另一 Thread 的共享历史：不压缩。
      return null;
    }
    Entry latestResultEntry = turnResultEntry(path, latestStart);
    if (latestResultEntry != null
        && latestResultEntry.payload() instanceof AssistantErrorPayload error
        && ProviderErrorKind.OVERFLOW.name().equals(error.error().code())) {
      if (latestStartPayload.contextWindow() == null) {
        return null;
      }
      if (isOverflowRecoveryRetry(path, latestTurn)) {
        // 一次 OVERFLOW 只允许 compact + immediate CONTINUATION 重试一次；重试仍 overflow 时保留失败结果，
        // 绝不进入无界 compaction/retry 循环。后续新的 INPUT 或 tool continuation 仍可独立触发压缩。
        return null;
      }
      return compactionPlanner
          .prepare(path, CompactionTrigger.OVERFLOW, latestStartPayload.contextWindow())
          .orElse(null);
    }
    return thresholdPreparation(thread, path, latestTurn, compaction);
  }

  /**
   * 最近一次 complete compaction 是 threshold freshness barrier；失败、STOPPED、incomplete compaction 与
   * Resolver Rejected turn（{@code contextWindow == null}）不抹掉更早成功 usage。其它 Thread 拥有的共享 turn 是
   * ownership barrier。
   */
  private CompactionPreparation thresholdPreparation(
      ThreadState thread, EntryPath path, ClosedTurn latestTurn, CompactionConfig compaction) {
    ClosedTurn candidate = latestTurn;
    while (candidate != null) {
      TurnStartPayload start = (TurnStartPayload) candidate.start().payload();
      if (start.reason() == TurnStartReason.COMPACTION) {
        CompactionPayload result = compactionResultOfTurn(path, candidate.start());
        if (result != null && result.complete()) {
          return null;
        }
        candidate = previousClosedTurn(path, candidate);
        continue;
      }
      if (!thread.id().equals(start.ownerThreadId())) {
        return null;
      }
      if (start.contextWindow() == null) {
        candidate = previousClosedTurn(path, candidate);
        continue;
      }
      Entry resultEntry = turnResultEntry(path, candidate.start());
      if (resultEntry != null
          && resultEntry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT
          && message.assistantMetadata() != null) {
        ModelUsage usage = message.assistantMetadata().usage();
        long contextTokens =
            usage.providerTotalTokens() > 0
                ? usage.providerTotalTokens()
                : usage.categorizedTokens();
        long threshold = Math.max(0L, (long) start.contextWindow() - compaction.reserveTokens());
        if (contextTokens <= threshold) {
          return null;
        }
        return compactionPlanner
            .prepare(path, CompactionTrigger.THRESHOLD, start.contextWindow())
            .orElse(null);
      }
      candidate = previousClosedTurn(path, candidate);
    }
    return null;
  }

  private static ClosedTurn previousClosedTurn(EntryPath path, ClosedTurn turn) {
    int startIndex = indexOfEntry(path.entries(), turn.start().id());
    return startIndex < 0 ? null : latestClosedTurn(path, startIndex);
  }

  /**
   * 路径上最新已关闭 turn 的 assistant 结果 Entry（ASSISTANT MESSAGE / ASSISTANT_ERROR / ASSISTANT_ABORTED）；无则
   * null。
   */
  private static Entry turnResultEntry(EntryPath path, Entry turn) {
    boolean inTurn = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(turn.id())) {
        inTurn = true;
        continue;
      }
      if (!inTurn) {
        continue;
      }
      EntryPayload payload = entry.payload();
      if (payload instanceof TurnEndPayload) {
        return null;
      }
      if (payload instanceof MessagePayload message) {
        if (message.message().role() == AgentMessageRole.ASSISTANT) {
          return entry;
        }
      } else if (payload instanceof AssistantErrorPayload
          || payload instanceof AssistantAbortedPayload) {
        return entry;
      }
    }
    return null;
  }

  /** {@code beforeExclusive} 之前最近的已关闭 turn；该范围末尾仍是 open turn / 无任何 turn 时返回 null。 */
  private static ClosedTurn latestClosedTurn(EntryPath path, int beforeExclusive) {
    for (int i = beforeExclusive - 1; i >= 0; i--) {
      EntryPayload payload = path.entries().get(i).payload();
      if (payload instanceof TurnEndPayload end) {
        for (int j = i - 1; j >= 0; j--) {
          Entry entry = path.entries().get(j);
          if (entry.id().equals(end.turnStartEntryId())) {
            return new ClosedTurn(entry, end);
          }
        }
        return null;
      }
      if (payload instanceof TurnStartPayload) {
        // 该范围末尾仍处于 open turn（classifier 保证主路径不会到达此分支）；防御性不触发。
        return null;
      }
    }
    return null;
  }

  /** 返回指定 turn 内唯一的 COMPACTION result payload；turn 以其它 assistant result 结束 / 无结果时返回 null。 */
  private static CompactionPayload compactionResultOfTurn(EntryPath path, Entry turn) {
    boolean inTurn = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(turn.id())) {
        inTurn = true;
        continue;
      }
      if (!inTurn) {
        continue;
      }
      EntryPayload payload = entry.payload();
      if (payload instanceof TurnEndPayload
          || payload instanceof AssistantErrorPayload
          || payload instanceof AssistantAbortedPayload
          || payload instanceof MessagePayload) {
        return null;
      }
      if (payload instanceof CompactionPayload compaction) {
        return compaction;
      }
    }
    return null;
  }

  /** 当前失败 turn 是否正是 complete OVERFLOW compaction 创建的 immediate CONTINUATION 重试。 */
  private static boolean isOverflowRecoveryRetry(EntryPath path, ClosedTurn latestTurn) {
    if (((TurnStartPayload) latestTurn.start().payload()).reason()
        != TurnStartReason.CONTINUATION) {
      return false;
    }
    int latestStartIndex = indexOfEntry(path.entries(), latestTurn.start().id());
    if (latestStartIndex < 0) {
      return false;
    }
    ClosedTurn previousTurn = latestClosedTurn(path, latestStartIndex);
    if (previousTurn == null
        || previousTurn.end().outcome() != TurnEndOutcome.COMPLETED
        || !previousTurn.end().continueModel()
        || ((TurnStartPayload) previousTurn.start().payload()).reason()
            != TurnStartReason.COMPACTION) {
      return false;
    }
    CompactionPayload previousCompaction = compactionResultOfTurn(path, previousTurn.start());
    return previousCompaction != null
        && previousCompaction.complete()
        && previousCompaction.trigger() == CompactionTrigger.OVERFLOW;
  }

  private static int indexOfEntry(List<Entry> entries, UUID entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }

  private static ModelAttemptSnapshot modelAttemptSnapshot(ModelInvocation model) {
    if (model.failedAttempts().size() == model.attempt()) {
      return null;
    }
    if (model.streamCheckpoint() == null) {
      return new ModelAttemptSnapshot(model.attempt(), 0, "", "");
    }
    return new ModelAttemptSnapshot(
        model.streamCheckpoint().attempt(),
        model.streamCheckpoint().sequence(),
        model.streamCheckpoint().text(),
        model.streamCheckpoint().thinking());
  }

  /**
   * Terminal Model 原子应用（Thread -&gt; Model -&gt; Tool -&gt; Work 锁序），单 action 恰好执行一次并完成 claim。
   *
   * <p>关闭 turn 的路径（COMPLETE 无 calls / LENGTH 无 calls / FILTERED / terminal failure / cancel /
   * unknown / compaction 关闭结果）在同一事务追加 TURN_END 后执行严格物化校验（attach-then-delete，经 Store 的 {@link
   * ModelAttemptMaterialization} 校验）并物理删除 ModelInvocation；只有 active Tool phase（SUCCEEDED 带 calls）保留
   * parent 并 attach resultEntryId。
   *
   * <p>wake 决定：active Tool phase 只为 READY 槽位请求 TOOL Work（全部 immediate terminal 则请求 THREAD 让 batch 经
   * ToolTerminalPending 应用并反馈模型）；closed turn 在新 Entries 插入、Thread advanced 后用新 head path 判定闭合是否立即
   * 出现 compaction action（如普通成功 turn 刚越过 threshold 自动自压），只要已有 queued message、成功 HISTORY / OVERFLOW
   * compaction 需要延续、或 compaction 立即到期就先请求 THREAD 再 complete，由 wakeVersion 保留新 wake；失败 / 停止 /
   * complete COMPACTION 由 planner 判定不 spin，绝不 always wake。新命令在事务后到达会自行 requestWork。
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
        || !thread.headEntryId().equals(model.basisHeadEntryId())) {
      // 决策与执行同事务，理论不可达；改为明确的不变量失败，绝不降级为循环重试。
      throw new IllegalStateException(
          "terminal model apply preconditions changed under the same transaction for model "
              + model.id());
    }
    Instant mutationNow =
        durableMutationTime(now, thread.updatedAt(), path.head().createdAt(), model.updatedAt());
    UUID sessionId = path.root().sessionId();
    UUID parentId = ModelAttemptFailureAppender.append(tx, sessionId, path.head().id(), model);
    boolean succeeded = model.status() == ModelInvocationStatus.SUCCEEDED;
    ProviderResponse response = model.result();
    CompactionRequest compactionRequest = model.request().compaction();
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            resultEntryId,
            sessionId,
            parentId,
            succeeded
                ? compactionRequest != null
                    ? CompactionSummaryAssembler.resultPayload(
                        compactionRequest, response.text(), path)
                    : payloadMapper.assistantPayload(response, model.request().toolBindings())
                : payloadMapper.assistantErrorPayload(model.error(), modelAttemptSnapshot(model)),
            mutationNow));
    UUID head = resultEntryId;
    boolean toolPhase = false;
    List<ToolInvocation> invocations = List.of();
    // 关闭的压缩 turn 是否需要 THREAD 延续：HISTORY 部分成功（TURN_PREFIX 义务）或 OVERFLOW 最终压缩 continueModel=true。
    boolean compactionWake = false;
    if (succeeded && compactionRequest != null) {
      // 压缩 turn：成功结果固定无 tool call；HISTORY 部分成功固定 continueModel=false，只有 complete 最终压缩且
      // OVERFLOW 触发时才 continueModel=true（让既有 CONTINUATION 重试失败 turn）。
      boolean continueModel =
          compactionRequest.phase() != CompactionPhase.HISTORY
              && compactionRequest.trigger() == CompactionTrigger.OVERFLOW;
      compactionWake = continueModel || compactionRequest.phase() == CompactionPhase.HISTORY;
      UUID turnEndId = tx.nextId();
      tx.insertEntry(
          new Entry(
              turnEndId,
              sessionId,
              resultEntryId,
              new TurnEndPayload(
                  model.turnStartEntryId(), TurnEndOutcome.COMPLETED, continueModel, null, null),
              mutationNow));
      head = turnEndId;
    } else if (succeeded) {
      // canonical response 已在 SUCCEEDED 前通过 validator；这里只按 planner 的纯决策落地。
      ModelResponsePlan plan = responsePlanner.plan(response, model.request().toolBindings());
      switch (plan) {
        case ModelResponsePlan.Completed ignored -> {
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  sessionId,
                  resultEntryId,
                  new TurnEndPayload(
                      model.turnStartEntryId(), TurnEndOutcome.COMPLETED, false, null, null),
                  mutationNow));
          head = turnEndId;
        }
        case ModelResponsePlan.Failed failed -> {
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  sessionId,
                  resultEntryId,
                  new TurnEndPayload(
                      model.turnStartEntryId(),
                      TurnEndOutcome.FAILED,
                      false,
                      failed.reason(),
                      null),
                  mutationNow));
          head = turnEndId;
        }
        case ModelResponsePlan.ToolBatch batch -> {
          toolPhase = true;
          // active Tool phase：attach resultEntryId 并保留 parent，插入全部 sibling。
          tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
          List<ToolInvocation> materialized = new ArrayList<>(batch.tools().size());
          Map<PluginStateKey, PluginStateAccessMode> seenStateAccesses = new HashMap<>();
          for (int ordinal = 0; ordinal < batch.tools().size(); ordinal++) {
            ModelResponsePlan.ToolSlot slot = batch.tools().get(ordinal);
            ToolInvocationStatus status = slot.status();
            ToolInvocationError error = slot.error();
            if (status == ToolInvocationStatus.READY) {
              // READY 槽位的 binding 由 planner 保证非空；plugin sibling 状态冲突仍在 Thread 边界确定性拒绝。
              ToolInvocationError conflict =
                  siblingStateConflict(slot.binding().plugin(), seenStateAccesses);
              if (conflict != null) {
                status = ToolInvocationStatus.FAILED;
                error = conflict;
              }
            }
            UUID toolId = tx.nextId();
            materialized.add(
                new ToolInvocation(
                    toolId,
                    model.id(),
                    resultEntryId,
                    ordinal,
                    slot.call(),
                    slot.binding(),
                    status,
                    0,
                    null,
                    null,
                    ToolEffectBatch.EMPTY,
                    error,
                    mutationNow,
                    mutationNow));
          }
          tx.insertToolInvocations(materialized);
          invocations = materialized;
        }
      }
    } else {
      UUID turnEndId = tx.nextId();
      tx.insertEntry(
          new Entry(
              turnEndId,
              sessionId,
              resultEntryId,
              new TurnEndPayload(
                  model.turnStartEntryId(),
                  TurnEndOutcome.FAILED,
                  false,
                  TurnEndReason.TURN_FAILED,
                  null),
              mutationNow));
      head = turnEndId;
    }
    ThreadState advancedThread = thread.advanceHead(head, mutationNow);
    boolean requestThread = false;
    if (!toolPhase) {
      // 关闭 turn：同事务 attach-then-delete 完成严格物化校验并删除 Model 行（closed turn 不保留 Invocation）。
      tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
      tx.deleteModelInvocation(model.id());
      // 闭合后用新 head path 判断是否立即出现 compaction action（如普通成功 turn 刚越过 threshold 自动自压）；失败 /
      // 停止 / complete 的 COMPACTION 由 planner 返回 null 保持不 spin，HISTORY / OVERFLOW 既有规则不变。绝不 always
      // wake。
      boolean compactionDue = compactionPreparation(advancedThread, tx.loadEntryPath(head)) != null;
      requestThread = hasQueuedMessage || compactionWake || compactionDue;
    }
    tx.updateThread(advancedThread);
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
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, invocation.id()), now);
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

  /**
   * Tool sibling 原子应用：全部 terminal 才执行，按 ordinal 通过统一 appender 追加 effects + ToolResult，再追加 COMPLETED
   * TURN_END(continueModel=true)；同一事务删除全部 child ToolInvocation 与 parent ModelInvocation并固定先请求
   * THREAD 再 complete，下一 claim 才做 continuation。数量 / ordinal 前缀 / ownership / terminal 任一违反即抛错回滚；删除
   * parent 前先执行 与 {@link ModelAttemptMaterialization} 等价的最小严格校验（attached Assistant/result 与已物化失败
   * attempt 前缀），绝不 绕过校验直接 delete。低序 mutation 完成后最后执行 claimed THREAD Work fence。
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
      if (siblings.get(i).ordinal() != i) {
        throw new IllegalStateException(
            "tool siblings must be a contiguous ordinal prefix of entry " + assistant.id());
      }
      ToolInvocation sibling = siblings.get(i);
      if (!sibling.modelInvocationId().equals(model.id()) || !sibling.status().isTerminal()) {
        // 锁内复查：任何不一致都是不变量违反，回滚（绝不部分 apply）。
        throw new IllegalStateException(
            "tool siblings changed under lock for assistant entry " + assistant.id());
      }
    }
    // 删除 parent 前的严格物化校验：attached Assistant/result 与已物化失败 attempt 前缀必须与 immutable 事实一致。
    ModelAttemptMaterialization.validateAttached(model, path);
    Instant mutationNow =
        durableMutationTime(now, thread.updatedAt(), path.head().createdAt(), model.updatedAt());
    for (ToolInvocation sibling : siblings) {
      mutationNow = durableMutationTime(mutationNow, sibling.updatedAt());
    }
    UUID sessionId = path.root().sessionId();
    UUID parentId = path.head().id();
    for (ToolInvocation sibling : siblings) {
      ToolOutcomeAppender.Applied applied =
          ToolOutcomeAppender.append(
              tx, sessionId, parentId, sibling, mutationNow, toolResultHistoryMaterializer);
      parentId = applied.headEntryId();
    }
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            sessionId,
            parentId,
            new TurnEndPayload(
                model.turnStartEntryId(), TurnEndOutcome.COMPLETED, true, null, null),
            mutationNow));
    tx.updateThread(thread.advanceHead(turnEndId, mutationNow));
    // children 先于 parent 删除（FK 顺序）。
    tx.deleteToolInvocationsByIds(siblings.stream().map(ToolInvocation::id).toList());
    tx.deleteModelInvocation(model.id());
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
    if (reason == TurnStartReason.CONTINUATION
        && (path.openTurnStart().isPresent()
            || !(path.head().payload() instanceof TurnEndPayload end && end.continueModel()))) {
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
    ProcessorLeaseSupport.ensureLeaseMargin(tx, claim, claimed, config.leaseConfig(), now);
    Instant planNow = durableMutationTime(now, thread.updatedAt(), path.head().createdAt());
    return planBuilder.build(thread.id(), path, reason, queued, tx::nextId, planNow, preparation);
  }

  /**
   * 事务外解析 + 第二事务 CAS 提交：Resolver 异常 / null / heartbeat 调度失败按失败延迟 reschedule（零 durable mutation）；提交
   * CAS（source head / cutoff 内 Command 快照 / claim）失败抛 {@link ClaimLostSignal} 由 {@link #process}
   * 映射为 LOST。YOLO 变化不使 plan 失效：commit 以第二事务锁到的 Thread 当前 YOLO 为准。成功后本 claim 已消费，返回 COMPLETED。
   */
  private ThreadProcessResult resolveAndCommit(ClaimedWork claim, TurnPlan plan) {
    AtomicBoolean heartbeatLost = new AtomicBoolean();
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            store, scheduler, config.leaseConfig(), clock, () -> heartbeatLost.set(true));
    if (!heartbeat.start(claim)) {
      log.warn("cannot schedule work lease heartbeat for {}; rescheduling", claim.target());
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ThreadProcessResult.RESCHEDULED
          : ThreadProcessResult.LOST_OWNERSHIP;
    }
    TurnResolver.Result result;
    try {
      result = resolver.resolve(plan.threadId(), plan.candidatePath(), plan.preparation());
    } catch (RuntimeException failure) {
      log.warn(
          "turn resolver failed for thread {}; rescheduling its work", plan.threadId(), failure);
      result = null;
    } finally {
      heartbeat.stop();
    }
    if (result == null || heartbeatLost.get()) {
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ThreadProcessResult.RESCHEDULED
          : ThreadProcessResult.LOST_OWNERSHIP;
    }
    if (result instanceof TurnResolver.Resolved resolved) {
      // Harness 边界校验：任何不一致都是 Resolver 契约错误，抛 ISE 且此刻零 durable mutation（绝不转 typed rejection）。
      ResolvedRequestValidator.validate(plan, resolved);
    }
    commit(claim, plan, result);
    return ThreadProcessResult.COMPLETED;
  }

  /**
   * 第二事务 CAS 提交。要求当前 Thread head == planned source head、cutoff 内 queued Command 与 planned
   * 快照逐字段相等（允许 sequence &gt; cutoff 的新命令，不 CAS revision / nextCommandSequence），claim token 活跃；最终
   * Thread 更新使用第二事务锁到的当前 YOLO（speculative plan 创建时的旧值绝不写回）并保留其最新 nextCommandSequence， revision 精确
   * +1。锁序为 Thread -&gt; Commands -&gt; Model -&gt; Work：全部低序 mutation 先完成，claimed THREAD Work 的
   * final fence 最后执行（失败抛 {@link ClaimLostSignal} 整事务回滚）。任何 head / 快照 / claim 损失一律抛 {@link
   * ClaimLostSignal} 回滚（零 durable mutation），由 {@link #process} 映射为 LOST_OWNERSHIP。
   *
   * <p>resolved：同层 Work 按 (type, id) 升序（先 THREAD wake 再 MODEL Work）只请求 MODEL Work——绝不因 deferred
   * messages 制造无意义 THREAD claim（terminal apply 会按 queued 快照重建 wake）——然后 complete。rejected：追加
   * error+TURN_END 后，若 plan 保留了 deferred messages 或闭合后用新 head path 判定立即出现 compaction action （更早
   * usage 超阈值）则先请求 THREAD 再 complete，否则直接 complete。
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
    ThreadState thread = tx.lockThread(plan.threadId()).orElse(null);
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
    Instant mutationNow = durableMutationTime(now, thread.updatedAt());
    for (Entry entry : plan.candidateEntries()) {
      mutationNow = durableMutationTime(mutationNow, entry.createdAt());
    }
    Integer contextWindow =
        result instanceof TurnResolver.Resolved resolved ? resolved.contextWindow() : null;
    // 低序 mutation（Entries / Commands / Thread / ModelInvocation）先完成。
    for (Entry entry : plan.candidateEntries()) {
      tx.insertEntry(withCreatedAt(withResolvedTurnStart(entry, plan, contextWindow), mutationNow));
    }
    List<ThreadCommand> consumed = new ArrayList<>(plan.consumedCommands().size());
    for (ThreadCommand command : plan.consumedCommands()) {
      consumed.add(command.consume(plan.turnStartEntryId()));
    }
    tx.updateCommands(consumed);
    UUID invocationId = null;
    if (result instanceof TurnResolver.Resolved resolved) {
      ThreadState advanced = thread.advanceHead(plan.candidateHeadEntryId(), mutationNow);
      tx.updateThread(advanced);
      invocationId = tx.nextId();
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
      ThreadState advanced = thread.advanceHead(turnEndId, mutationNow);
      tx.updateThread(advanced);
      // 低序 mutation 完成后，final fence 前基于新 head path 判断闭合后是否立即出现 compaction action（如更早
      // usage 已超阈值）；Rejected turn 本身（contextWindow==null）不作为触发事实。
      boolean compactionDue = compactionPreparation(advanced, tx.loadEntryPath(turnEndId)) != null;
      if (tx.lockClaimedWork(claim, now).isEmpty()) {
        throw new ClaimLostSignal();
      }
      // rejected：保留 deferred messages 或闭合即触发 compaction 时先请求 THREAD 再 complete。
      if (plan.hasDeferredMessages() || compactionDue) {
        tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
      }
      tx.completeWork(claim, now);
      return;
    }
    // resolved：final fence 后同层 Work 按 (type, id) 升序只请求 MODEL Work 再 complete（绝不因 deferred
    // messages 制造无意义 THREAD claim；terminal apply 会按 queued 快照重建 wake）。
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId), now);
    tx.completeWork(claim, now);
  }

  /** cutoff 内 queued Command 与 planned 快照逐字段相等（id/thread/sequence/payload/client ID/state）。 */
  private static boolean snapshotMatches(TurnPlan plan, List<ThreadCommand> queued) {
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
          || !planned.clientCommandId().equals(command.clientCommandId())
          || planned.state() != command.state()) {
        return false;
      }
    }
    return withinCutoff == plan.plannedCommands().size();
  }

  /** 将 wall-clock 样本抬升到所有已锁定 durable 事实的时间下界。 */
  private static Instant durableMutationTime(Instant candidate, Instant... floors) {
    Instant effective = Objects.requireNonNull(candidate, "candidate");
    for (Instant floor : floors) {
      Instant requiredFloor = Objects.requireNonNull(floor, "floor");
      if (effective.isBefore(requiredFloor)) {
        effective = requiredFloor;
      }
    }
    return effective;
  }

  private static Entry withCreatedAt(Entry entry, Instant createdAt) {
    return new Entry(
        entry.id(), entry.sessionId(), entry.parentEntryId(), entry.payload(), createdAt);
  }

  /** 第二阶段 commit 在插入前补齐 Resolver 成功时的 contextWindow；rejected 保持 null。 */
  private static Entry withResolvedTurnStart(Entry entry, TurnPlan plan, Integer contextWindow) {
    if (!entry.id().equals(plan.turnStartEntryId())
        || !(entry.payload() instanceof TurnStartPayload start)) {
      return entry;
    }
    return new Entry(
        entry.id(),
        entry.sessionId(),
        entry.parentEntryId(),
        new TurnStartPayload(
            start.reason(), start.settings(), start.ownerThreadId(), contextWindow),
        entry.createdAt());
  }

  /**
   * 同一 Assistant 的 sibling 都读取相同冻结 branch。若某 state key 已出现 WRITE，后续 READ/WRITE 必然读取陈旧快照， 因而在
   * dispatch 前确定性拒绝；READ 后 WRITE 与不同 key 保持并发。
   */
  private static ToolInvocationError siblingStateConflict(
      PluginToolBinding plugin, Map<PluginStateKey, PluginStateAccessMode> seen) {
    if (plugin == null || plugin.stateAccesses().isEmpty()) {
      return null;
    }
    for (PluginStateAccess access : plugin.stateAccesses()) {
      PluginStateKey key = new PluginStateKey(plugin.pluginId(), access.customType());
      if (seen.get(key) == PluginStateAccessMode.WRITE) {
        return new ToolInvocationError(
            "SIBLING_STATE_CONFLICT",
            "A previous sibling tool writes plugin state "
                + key
                + "; call this tool in the next model turn.");
      }
    }
    for (PluginStateAccess access : plugin.stateAccesses()) {
      PluginStateKey key = new PluginStateKey(plugin.pluginId(), access.customType());
      if (access.mode() == PluginStateAccessMode.WRITE) {
        seen.put(key, PluginStateAccessMode.WRITE);
      } else {
        seen.putIfAbsent(key, PluginStateAccessMode.READ);
      }
    }
    return null;
  }

  private record PluginStateKey(String pluginId, String customType) {
    @Override
    public String toString() {
      return pluginId + ":" + customType;
    }
  }

  private record ClosedTurn(Entry start, TurnEndPayload end) {}

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
              tx.rescheduleWork(claim, now, now.plus(delay));
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
