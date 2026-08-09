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
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Target Thread processor：消费 dispatcher 已 claim 的 THREAD Work，以固定优先级驱动 Thread 的完整 Agent Loop。
 *
 * <p>每步短事务先用纯 {@link ThreadContextClassifier} 把当前 Thread 分类为唯一的 live/historical 适用性上下文 （{@link
 * ThreadContext}，unlocked 读：Model 只按 (threadId, open TURN_START) 查找，Tool siblings 只在 Model 结果恰为当前
 * Assistant head 时加载），再按上下文执行：(a) MODEL_TERMINAL_PENDING —— head 恰为 basis 且未挂结果的 terminal
 * ModelInvocation 原子 apply；(b) TOOL_TERMINAL_PENDING —— 当前 head 恰为本 Thread ModelInvocation 产出 的
 * Assistant Entry、且其 Tool siblings 全部 terminal 且全部未挂结果时按 ordinal 原子 apply；(c) MODEL_ACTIVE /
 * TOOL_ACTIVE —— 当前 applicable Model/Tool invocation 非 terminal 时完成 claim 并返回 SUSPENDED；(d)
 * CONTINUATION_DUE —— 无 open Turn 且 head 为 continueModel=true 的 TURN_END 时启动 continuation；(e)
 * queued USER_MESSAGE/CUSTOM_MESSAGE 存在时启动 INPUT Turn（IDLE_OR_HISTORICAL）；(f) 否则完成 claim 返回
 * QUIESCENT。旧 / 历史 open Turn 只在启动 新 INPUT Turn 时被 normalization，绝不恢复 / 复用。不变量被破坏的形状由分类器以 ISE
 * 拒绝，绝不降级为业务上下文。
 *
 * <p>Turn 启动采用 speculative plan：短事务锁 Thread、读取 queued Command 快照与 cutoff、校验 claim（并对近过期 lease 做
 * {@link ProcessorLeaseSupport#ensureLeaseMargin} 保证首次 Resolver heartbeat 前不会过期）、分配 candidate Entry
 * ID 并构造完整合法 candidate EntryPath（不写任何 durable 状态）；事务外调用 {@link TurnResolver}（期间由本地 {@link
 * WorkHeartbeat} 维持 lease）；第二短事务以 source head / source YOLO / cutoff 内 Command 精确快照 / claim
 * ownership 做 CAS，一次性原子提交 normalization + TURN_START + Message + Command markers + Thread 更新 +
 * ModelInvocation/MODEL Work（resolved）或 AssistantError + FAILED TURN_END（rejected）。Resolved
 * 请求在提交前先经 {@link ResolvedRequestValidator} 按 candidate branch 事实（yolo / route / model / variant /
 * tools）做机械一致性 校验，不一致即抛错且零 durable mutation（绝不转 typed rejection）。任何 CAS / claim 损失一律 完整 no-op 返回
 * LOST_OWNERSHIP；Resolver 异常 / null / heartbeat 调度失败与 step limit 按单一正失败延迟 reschedule， 绝不静默丢弃
 * Work。duplicate / stale THREAD claim 是 no-op。
 *
 * <p>锁序与 final fence：Model terminal apply / Tool sibling batch / resolve commit 都在同一事务内先完成全部低序
 * mutation（Thread -&gt; Commands -&gt; ModelInvocation -&gt; ToolInvocation siblings），claimed
 * THREAD Work 的 最终 fence 最后执行；fence 失败抛出内部 {@link ClaimLostSignal} 使事务完整回滚，再由 {@link #process} 映射为
 * LOST_OWNERSHIP，绝不存在带 durable mutation 的 LOST 提交。commit 与 applyModel 在同层 Work 中按 (type, id) 升序请求（先
 * THREAD wake 再 MODEL / TOOL Work）。历史 / 非 applicable open Turn（head 不在 applicable 位置） 在分类阶段只使用
 * unlocked 读，绝不先锁 Model/Tool 再落到 Commands / INPUT normalization。
 *
 * <p>Model terminal apply：SUCCEEDED 追加 ASSISTANT Entry 并挂 resultEntryId，无 ToolCall 时追加 COMPLETED
 * TURN_END(continueModel=false)；有 ToolCall 时按 response ordinal materialize ToolInvocation（通常为
 * READY；命中 plugin sibling WRITE 后再 READ/WRITE 的调用直接为 unattached FAILED），仅为 READY 请求 TOOL Work 且不写
 * TURN_END；FAILED / CANCELLED / UNKNOWN 追加 AssistantError 与 FAILED TURN_END。Tool sibling 应用绝不部分
 * apply：数量 / ordinal 前缀 / ownership / 全部 terminal 且全部未挂 result 任一违反即抛错回滚。每次原子应用 Thread
 * head/revision 只 +1；Model / Tool processor 不写 Entry/head。
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
  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();
  private final TurnPlanBuilder planBuilder = new TurnPlanBuilder();
  private final ClaimAdmissionGuard admissionGuard = new ClaimAdmissionGuard();
  private final ThreadContextClassifier contextClassifier = new ThreadContextClassifier();
  private final CompactionPlanner compactionPlanner;

  public ThreadProcessor(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.compactionPlanner = new CompactionPlanner(config.compaction());
  }

  /**
   * 处理一次 dispatcher 已 claim 的 THREAD Work。
   *
   * <p>先用 Work-only 短事务验证 claim 当前真实 owned，再进 per-thread admission guard（同一 claim 重复 / 并发投递一律 LOST
   * no-op；不同新 token 抢占 guard）。随后运行有界步骤循环：终端应用 / Tool batch / rejected 提交继续同 claim 循环， blocker /
   * resolved / quiescent 完成 claim，step limit 或 Resolver 临时失败按延迟 reschedule。事务内 final fence 丢失抛出的
   * {@link ClaimLostSignal} 在事务完整回滚后在此捕获并映射为 LOST_OWNERSHIP。
   */
  public ThreadProcessResult process(ClaimedWork claim) {
    Objects.requireNonNull(claim, "claim");
    if (claim.target().type() != WorkTargetType.THREAD) {
      throw new IllegalArgumentException(
          "ThreadProcessor requires a THREAD work claim, got " + claim.target());
    }
    long threadId = claim.target().id();
    String token = claim.leaseToken();
    if (!claimOwned(claim)) {
      return ThreadProcessResult.LOST_OWNERSHIP;
    }
    if (!admissionGuard.tryAdmit(threadId, token)) {
      return ThreadProcessResult.LOST_OWNERSHIP;
    }
    try {
      return runLoop(claim);
    } catch (ClaimLostSignal lost) {
      return ThreadProcessResult.LOST_OWNERSHIP;
    } finally {
      admissionGuard.release(threadId, token);
    }
  }

  private ThreadProcessResult runLoop(ClaimedWork claim) {
    for (int step = 0; step < config.stepLimit(); step++) {
      LoopStep outcome = step(claim);
      if (outcome instanceof LoopStep.Continue) {
        continue;
      }
      if (outcome instanceof LoopStep.Plan plan) {
        switch (resolveAndCommit(claim, plan.plan())) {
          case RESOLVED_COMMITTED -> {
            return ThreadProcessResult.SUSPENDED;
          }
          case REJECTED_COMMITTED -> {
            continue;
          }
          case RESCHEDULED -> {
            return ThreadProcessResult.RESCHEDULED;
          }
          case LOST -> {
            return ThreadProcessResult.LOST_OWNERSHIP;
          }
        }
      }
      if (outcome instanceof LoopStep.Suspend) {
        return ThreadProcessResult.SUSPENDED;
      }
      if (outcome instanceof LoopStep.Quiescent) {
        return ThreadProcessResult.QUIESCENT;
      }
      if (outcome instanceof LoopStep.Lost) {
        return ThreadProcessResult.LOST_OWNERSHIP;
      }
    }
    log.warn(
        "thread {} hit the step limit of {}; rescheduling its work",
        claim.target().id(),
        config.stepLimit());
    return rescheduleIfOwned(claim, config.resolveFailureDelay())
        ? ThreadProcessResult.RESCHEDULED
        : ThreadProcessResult.LOST_OWNERSHIP;
  }

  /** 单步短事务：锁 Thread -&gt;（Model -&gt; Tool）-&gt; Work，按固定优先级决定并执行一个动作。 */
  private LoopStep step(ClaimedWork claim) {
    return store.transaction(tx -> stepTx(tx, claim));
  }

  private LoopStep stepTx(HarnessStore.Transaction tx, ClaimedWork claim) {
    Instant now = clock.instant();
    ThreadState thread = tx.lockThread(claim.target().id()).orElse(null);
    if (thread == null) {
      return new LoopStep.Lost();
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
          && model.resultEntryId() == thread.headEntryId()
          && path.head().payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        siblings = tx.loadToolInvocationsByAssistantEntryId(path.head().id());
      }
    }
    // 唯一的 live/historical 适用性来源：不变量被破坏的形状由分类器以 ISE 拒绝，绝不降级为业务上下文。
    ThreadContext context = contextClassifier.classify(thread, path, model, siblings);
    return switch (context) {
      case ThreadContext.ModelTerminalPending pending -> {
        ModelInvocation locked = tx.lockModelInvocation(pending.model().id()).orElse(null);
        if (locked == null) {
          yield new LoopStep.Lost();
        }
        yield applyModel(tx, claim, thread, path, locked, now);
      }
      case ThreadContext.ModelActive ignored -> {
        if (tx.lockClaimedWork(claim, now).isEmpty()) {
          yield new LoopStep.Lost();
        }
        tx.completeWork(claim, now);
        yield new LoopStep.Suspend();
      }
      case ThreadContext.ToolTerminalPending pending -> {
        ModelInvocation locked = tx.lockModelInvocation(pending.model().id()).orElse(null);
        if (locked == null) {
          yield new LoopStep.Lost();
        }
        List<ToolInvocation> lockedSiblings =
            tx.lockToolInvocationsByAssistantEntryId(pending.assistant().id());
        yield applyToolBatch(
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
      case ThreadContext.ToolActive ignored -> {
        if (tx.lockClaimedWork(claim, now).isEmpty()) {
          yield new LoopStep.Lost();
        }
        tx.completeWork(claim, now);
        yield new LoopStep.Suspend();
      }
      case ThreadContext.ContinuationDue ignored -> {
        // continuation 优先：现有 continueModel 义务必须原样执行，阈值压缩绝不插队（否则会吞掉既有 continuation）。
        // OVERFLOW 失败的 turn 已关闭为 FAILED/idle，仍会通过 IdleOrHistorical 的 overflow 路径压缩。
        yield planStep(tx, claim, thread, path, TurnStartReason.CONTINUATION, now);
      }
      case ThreadContext.IdleOrHistorical ignored -> {
        // 压缩优先于输入：到期压缩先执行（消费零 Command），不能被已 queued 的用户消息绕过。
        CompactionPreparation preparation = compactionPreparation(tx, thread, path);
        if (preparation != null) {
          yield planStep(tx, claim, thread, path, TurnStartReason.COMPACTION, preparation, now);
        }
        List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
        boolean hasInput = false;
        for (ThreadCommand command : queued) {
          if (command.type().isMessage()) {
            hasInput = true;
            break;
          }
        }
        if (hasInput) {
          yield planStep(tx, claim, thread, path, TurnStartReason.INPUT, now);
        }
        if (tx.lockClaimedWork(claim, now).isEmpty()) {
          yield new LoopStep.Lost();
        }
        tx.completeWork(claim, now);
        yield new LoopStep.Quiescent();
      }
    };
  }

  /**
   * 计算当前 Thread 是否应当启动一次压缩 turn（纯读、无锁写）。
   *
   * <p>只基于路径上最新已关闭 turn：该 turn 是 COMPACTION 时，只有其结果为 incomplete HISTORY payload 才机械启动
   * TURN_PREFIX（失败 / 停止 / 完成的压缩 turn 绝不立即再次压缩，避免 spin）；有 ModelInvocation 的非压缩 turn 必须由本 Thread
   * 自己拥有（共享历史 turn 属于另一 Thread 时不压缩），且 turn 结果 Entry 必须正是该 invocation 的 {@code resultEntryId}；无
   * ModelInvocation 的 Resolver Rejected turn 可跨越（normalized/relocated 历史形状不得借用其它后代结果）。最新 terminal
   * OVERFLOW 错误优先触发 overflow 压缩；否则在最近一次 complete COMPACTION barrier 之后向前寻找本 Thread 最新 SUCCEEDED
   * invocation，其 usage 超过 {@code max(0, contextWindow - reserveTokens)} 时阈值触发（providerTotalTokens 为
   * 0 时回退 categorizedTokens，全零不触发）。planner 无内容可摘要时返回 null。
   */
  private CompactionPreparation compactionPreparation(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    CompactionConfig compaction = config.compaction();
    if (!compaction.enabled()) {
      return null;
    }
    ClosedTurn latestTurn = latestClosedTurn(path, path.entries().size());
    if (latestTurn == null) {
      return null;
    }
    Entry latestStart = latestTurn.start();
    if (((TurnStartPayload) latestStart.payload()).reason() == TurnStartReason.COMPACTION) {
      // 机械延续：最新关闭的 COMPACTION turn 结果为 incomplete HISTORY payload 时启动第二次 TURN_PREFIX 调用。
      if (latestTurn.end().outcome() != TurnEndOutcome.COMPLETED) {
        return null;
      }
      CompactionPayload incomplete = compactionResultOfTurn(path, latestStart);
      if (incomplete == null || incomplete.phase() != CompactionPhase.HISTORY) {
        return null;
      }
      ModelInvocation history =
          tx.findModelInvocationByTurn(thread.id(), latestStart.id()).orElse(null);
      if (history == null || history.request().compaction() == null) {
        return null;
      }
      return compactionPlanner.prepareTurnPrefix(
          path, incomplete, history.request().contextWindow());
    }
    ModelInvocation latestInvocation =
        tx.findModelInvocationByTurn(thread.id(), latestStart.id()).orElse(null);
    if (latestInvocation == null) {
      if (tx.hasModelInvocationForTurn(latestStart.id())) {
        // 最新已关闭 turn 的 invocation 属于另一 Thread 的共享历史：不压缩。
        return null;
      }
    } else {
      Entry latestResultEntry = turnResultEntry(path, latestStart);
      if (latestInvocation.status().isTerminal()
          && (latestResultEntry == null
              || !Objects.equals(latestResultEntry.id(), latestInvocation.resultEntryId()))) {
        // normalized/relocated 历史形状不得借用其它后代结果。
        return null;
      }
      if (latestInvocation.status().isTerminal()
          && latestInvocation.error() != null
          && latestInvocation.error().kind() == ProviderErrorKind.OVERFLOW) {
        if (isOverflowRecoveryRetry(path, latestTurn)) {
          // 一次 OVERFLOW 只允许 compact + immediate CONTINUATION 重试一次；重试仍 overflow 时保留失败结果，
          // 绝不进入无界 compaction/retry 循环。后续新的 INPUT 或 tool continuation 仍可独立触发压缩。
          return null;
        }
        return compactionPlanner
            .prepare(path, CompactionTrigger.OVERFLOW, latestInvocation.request().contextWindow())
            .orElse(null);
      }
    }
    return thresholdPreparation(tx, thread, path, latestTurn, compaction);
  }

  /**
   * 最近一次 complete compaction 是 threshold freshness barrier；失败、STOPPED、incomplete compaction 与无
   * invocation 的 Resolver Rejected turn 不抹掉更早成功 usage。无本 Thread invocation、但存在其它 Thread invocation
   * 的共享 turn 是 ownership barrier。
   */
  private CompactionPreparation thresholdPreparation(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ClosedTurn latestTurn,
      CompactionConfig compaction) {
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
      ModelInvocation invocation =
          tx.findModelInvocationByTurn(thread.id(), candidate.start().id()).orElse(null);
      if (invocation == null) {
        if (tx.hasModelInvocationForTurn(candidate.start().id())) {
          return null;
        }
        candidate = previousClosedTurn(path, candidate);
        continue;
      }
      Entry resultEntry = turnResultEntry(path, candidate.start());
      if (invocation.status().isTerminal()
          && (resultEntry == null
              || !Objects.equals(resultEntry.id(), invocation.resultEntryId()))) {
        return null;
      }
      if (invocation.status() == ModelInvocationStatus.SUCCEEDED) {
        if (!(resultEntry.payload() instanceof MessagePayload message)
            || message.message().role() != AgentMessageRole.ASSISTANT) {
          return null;
        }
        ModelUsage usage = message.assistantMetadata().usage();
        long contextTokens =
            usage.providerTotalTokens() > 0
                ? usage.providerTotalTokens()
                : usage.categorizedTokens();
        long threshold =
            Math.max(0L, (long) invocation.request().contextWindow() - compaction.reserveTokens());
        if (contextTokens <= threshold) {
          return null;
        }
        return compactionPlanner
            .prepare(path, CompactionTrigger.THRESHOLD, invocation.request().contextWindow())
            .orElse(null);
      }
      if (!invocation.status().isTerminal()) {
        return null;
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
      if (entry.id() == turn.id()) {
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
          if (entry.id() == end.turnStartEntryId()) {
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
      if (entry.id() == turn.id()) {
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

  private static int indexOfEntry(List<Entry> entries, long entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id() == entryId) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Terminal Model 原子应用（Thread -&gt; Model -&gt; Tool -&gt; Work 锁序）。全部低序 mutation 完成后最后执行 claimed
   * THREAD Work fence，fence 失败抛 {@link ClaimLostSignal} 整事务回滚；fence 通过后按 id 升序请求 TOOL Work。
   */
  private LoopStep applyModel(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      Instant now) {
    if (!model.status().isTerminal()
        || model.resultEntryId() != null
        || thread.headEntryId() != model.basisHeadEntryId()) {
      // 决策与执行同事务，理论不可达；防御性回到循环重新决策。
      return new LoopStep.Continue();
    }
    long sessionId = path.root().sessionId();
    long parentId = path.head().id();
    boolean succeeded = model.status() == ModelInvocationStatus.SUCCEEDED;
    ProviderResponse response = model.result();
    CompactionRequest compactionRequest = model.request().compaction();
    long resultEntryId = tx.nextId();
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
                : payloadMapper.assistantErrorPayload(model.error()),
            now));
    tx.updateModelInvocation(model.attachResultEntry(resultEntryId, now));
    long head = resultEntryId;
    List<ToolInvocation> invocations = List.of();
    if (succeeded && compactionRequest != null) {
      // 压缩 turn：成功结果固定无 tool call；HISTORY 部分成功固定 continueModel=false，只有 complete 最终压缩且
      // OVERFLOW 触发时才 continueModel=true（让既有 CONTINUATION 重试失败 turn）。
      boolean continueModel =
          compactionRequest.phase() != CompactionPhase.HISTORY
              && compactionRequest.trigger() == CompactionTrigger.OVERFLOW;
      long turnEndId = tx.nextId();
      tx.insertEntry(
          new Entry(
              turnEndId,
              sessionId,
              resultEntryId,
              new TurnEndPayload(
                  model.turnStartEntryId(), TurnEndOutcome.COMPLETED, continueModel, null, null),
              now));
      head = turnEndId;
    } else if (succeeded && response.toolCalls().isEmpty()) {
      long turnEndId = tx.nextId();
      tx.insertEntry(
          new Entry(
              turnEndId,
              sessionId,
              resultEntryId,
              new TurnEndPayload(
                  model.turnStartEntryId(), TurnEndOutcome.COMPLETED, false, null, null),
              now));
      head = turnEndId;
    } else if (succeeded) {
      List<ProviderToolCall> calls = response.toolCalls();
      List<ToolInvocation> materialized = new ArrayList<>(calls.size());
      Map<PluginStateKey, PluginStateAccessMode> seenStateAccesses = new HashMap<>();
      for (int ordinal = 0; ordinal < calls.size(); ordinal++) {
        ProviderToolCall call = calls.get(ordinal);
        ToolBinding binding = bindingFor(model.request().toolBindings(), call.name());
        if (binding == null) {
          throw new IllegalStateException(
              "no frozen tool binding matches tool call "
                  + call.name()
                  + " of invocation "
                  + model.id());
        }
        long toolId = tx.nextId();
        ToolInvocationError conflict = siblingStateConflict(binding.plugin(), seenStateAccesses);
        ToolInvocationStatus status =
            conflict == null ? ToolInvocationStatus.READY : ToolInvocationStatus.FAILED;
        materialized.add(
            new ToolInvocation(
                toolId,
                model.id(),
                resultEntryId,
                ordinal,
                new ToolInvocationRequest(
                    new ToolCall(call.id(), call.name(), call.argumentsJson()), binding),
                status,
                0,
                null,
                null,
                ToolEffectBatch.EMPTY,
                conflict,
                null,
                now,
                now));
      }
      tx.insertToolInvocations(materialized);
      invocations = materialized;
    } else {
      long turnEndId = tx.nextId();
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
              now));
      head = turnEndId;
    }
    tx.updateThread(thread.advanceHead(head, thread.yoloEnabled(), now));
    // final fence 最后执行：损失抛内部信号，整事务回滚，绝无带 mutation 的 LOST 提交。
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    if (!invocations.isEmpty()) {
      List<ToolInvocation> ordered = new ArrayList<>(invocations);
      ordered.sort(Comparator.comparingLong(ToolInvocation::id));
      for (ToolInvocation invocation : ordered) {
        if (invocation.status() == ToolInvocationStatus.READY) {
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, invocation.id()), now);
        }
      }
    }
    return new LoopStep.Continue();
  }

  /**
   * Tool sibling 原子应用：全部 terminal 才执行，按 ordinal 通过统一 appender 追加 effects + ToolResult，再追加 COMPLETED
   * TURN_END(continueModel=true)。数量 / ordinal 前缀 / ownership / terminal / unattached 任一违反即抛错回滚；低序
   * mutation 完成后最后执行 claimed THREAD Work fence。
   */
  private LoopStep applyToolBatch(
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
      if (sibling.modelInvocationId() != model.id()
          || !sibling.status().isTerminal()
          || sibling.resultEntryId() != null) {
        // 锁内复查：任何不一致都是不变量违反，回滚（绝不部分 apply 或重新挂载）。
        throw new IllegalStateException(
            "tool siblings changed under lock for assistant entry " + assistant.id());
      }
    }
    long sessionId = path.root().sessionId();
    long parentId = path.head().id();
    List<ToolInvocation> updated = new ArrayList<>(siblings.size());
    for (ToolInvocation sibling : siblings) {
      ToolOutcomeAppender.Applied applied =
          ToolOutcomeAppender.append(tx, sessionId, parentId, sibling, now);
      updated.add(applied.invocation());
      parentId = applied.headEntryId();
    }
    long turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            sessionId,
            parentId,
            new TurnEndPayload(
                model.turnStartEntryId(), TurnEndOutcome.COMPLETED, true, null, null),
            now));
    tx.updateToolInvocations(updated);
    tx.updateThread(thread.advanceHead(turnEndId, thread.yoloEnabled(), now));
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    return new LoopStep.Continue();
  }

  /** 在步骤事务内构造 speculative plan；claim fence 与 lease margin 在构造前完成。 */
  private LoopStep planStep(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      TurnStartReason reason,
      Instant now) {
    return planStep(tx, claim, thread, path, reason, null, now);
  }

  /** COMPACTION turn 的 plan：切分事实由调用方传入（reason COMPACTION 时非空）。 */
  private LoopStep planStep(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      TurnStartReason reason,
      CompactionPreparation preparation,
      Instant now) {
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    if (reason == TurnStartReason.CONTINUATION) {
      if (path.openTurnStart().isPresent()
          || !(path.head().payload() instanceof TurnEndPayload end && end.continueModel())) {
        return new LoopStep.Continue();
      }
    } else if (reason != TurnStartReason.COMPACTION) {
      boolean hasInput = false;
      for (ThreadCommand command : queued) {
        if (command.type().isMessage()) {
          hasInput = true;
          break;
        }
      }
      if (!hasInput) {
        return new LoopStep.Continue();
      }
    }
    Work claimed = tx.lockClaimedWork(claim, now).orElse(null);
    if (claimed == null) {
      return new LoopStep.Lost();
    }
    // 近过期 claim 在 Resolver 首次 heartbeat 前可能过期：plan 事务内先确保完整 lease margin。
    ProcessorLeaseSupport.ensureLeaseMargin(tx, claim, claimed, config.leaseConfig(), now);
    TurnPlan plan =
        planBuilder.build(
            thread.id(), path, thread.yoloEnabled(), reason, queued, tx::nextId, now, preparation);
    return new LoopStep.Plan(plan);
  }

  /**
   * 事务外解析 + 第二事务 CAS 提交：Resolver 异常 / null / heartbeat 调度失败按失败延迟 reschedule（零 durable mutation）；提交
   * CAS（source head / source YOLO / cutoff 内 Command 快照 / claim）失败返回 LOST。
   */
  private ResolveOutcome resolveAndCommit(ClaimedWork claim, TurnPlan plan) {
    AtomicBoolean heartbeatLost = new AtomicBoolean();
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            store, scheduler, config.leaseConfig(), clock, () -> heartbeatLost.set(true));
    if (!heartbeat.start(claim)) {
      log.warn("cannot schedule work lease heartbeat for {}; rescheduling", claim.target());
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ResolveOutcome.RESCHEDULED
          : ResolveOutcome.LOST;
    }
    TurnResolver.Result result;
    try {
      result =
          resolver.resolve(
              plan.threadId(), plan.candidatePath(), plan.finalYoloEnabled(), plan.preparation());
    } catch (RuntimeException failure) {
      log.warn(
          "turn resolver failed for thread {}; rescheduling its work", plan.threadId(), failure);
      result = null;
    } finally {
      heartbeat.stop();
    }
    if (result == null || heartbeatLost.get()) {
      return rescheduleIfOwned(claim, config.resolveFailureDelay())
          ? ResolveOutcome.RESCHEDULED
          : ResolveOutcome.LOST;
    }
    if (result instanceof TurnResolver.Resolved resolved) {
      // Harness 边界校验：任何不一致都是 Resolver 契约错误，抛 ISE 且此刻零 durable mutation（绝不转 typed rejection）。
      ResolvedRequestValidator.validate(plan, resolved.request());
    }
    return switch (commit(claim, plan, result)) {
      case RESOLVED -> ResolveOutcome.RESOLVED_COMMITTED;
      case REJECTED -> ResolveOutcome.REJECTED_COMMITTED;
      case LOST -> ResolveOutcome.LOST;
    };
  }

  /**
   * 第二事务 CAS 提交。要求当前 Thread head == planned source head、当前 yolo == source yolo、cutoff 内 queued
   * Command 与 planned 快照逐字段相等（允许 sequence &gt; cutoff 的新命令，不 CAS revision / nextCommandSequence），
   * claim token 活跃；最终 Thread 更新基于当前锁定行并保留其最新 nextCommandSequence，revision 精确 +1。锁序为 Thread -&gt;
   * Commands -&gt; Model -&gt; Work：全部低序 mutation 先完成，claimed THREAD Work 的 final fence 最后执行 （失败抛
   * {@link ClaimLostSignal} 整事务回滚）；fence 通过后按 (type, id) 升序请求同层 Work（先 THREAD wake 再 MODEL Work）。
   */
  private CommitOutcome commit(ClaimedWork claim, TurnPlan plan, TurnResolver.Result result) {
    return store.transaction(tx -> commitTx(tx, claim, plan, result));
  }

  private CommitOutcome commitTx(
      HarnessStore.Transaction tx, ClaimedWork claim, TurnPlan plan, TurnResolver.Result result) {
    Instant now = clock.instant();
    ThreadState thread = tx.lockThread(plan.threadId()).orElse(null);
    if (thread == null) {
      return CommitOutcome.LOST;
    }
    if (thread.headEntryId() != plan.sourceHeadEntryId()
        || thread.yoloEnabled() != plan.sourceYoloEnabled()) {
      return CommitOutcome.LOST;
    }
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    if (!snapshotMatches(plan, queued)) {
      return CommitOutcome.LOST;
    }
    // 低序 mutation（Entries / Commands / Thread / ModelInvocation）先完成。
    for (Entry entry : plan.candidateEntries()) {
      tx.insertEntry(entry);
    }
    List<ThreadCommand> consumed = new ArrayList<>(plan.consumedCommands().size());
    for (ThreadCommand command : plan.consumedCommands()) {
      consumed.add(command.consume(plan.turnStartEntryId()));
    }
    tx.updateCommands(consumed);
    Long invocationId = null;
    if (result instanceof TurnResolver.Resolved resolved) {
      ThreadState advanced =
          thread.advanceHead(plan.candidateHeadEntryId(), plan.finalYoloEnabled(), now);
      tx.updateThread(advanced);
      invocationId = tx.nextId();
      tx.insertModelInvocation(
          new ModelInvocation(
              invocationId,
              thread.id(),
              plan.turnStartEntryId(),
              plan.candidateHeadEntryId(),
              resolved.request(),
              ModelInvocationStatus.READY,
              0,
              null,
              null,
              null,
              null,
              now,
              now));
    } else {
      TurnResolver.Rejected rejected = (TurnResolver.Rejected) result;
      long errorEntryId = tx.nextId();
      tx.insertEntry(
          new Entry(
              errorEntryId,
              plan.sessionId(),
              plan.candidateHeadEntryId(),
              new AssistantErrorPayload(rejected.error()),
              now));
      long turnEndId = tx.nextId();
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
      tx.updateThread(thread.advanceHead(turnEndId, plan.finalYoloEnabled(), now));
    }
    // final fence 最后执行：损失抛内部信号，整事务回滚（零 durable mutation 的 LOST）。
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      throw new ClaimLostSignal();
    }
    if (invocationId == null) {
      // rejected：不 complete，同 claim 继续循环（下一步 quiescent 时完成）。
      return CommitOutcome.REJECTED;
    }
    // 同层 Work 按 (type, id) 升序：先 THREAD wake 再 MODEL Work。
    if (plan.reason() == TurnStartReason.CONTINUATION && plan.hasDeferredMessages()) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    }
    tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId), now);
    tx.completeWork(claim, now);
    return CommitOutcome.RESOLVED;
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
          || planned.id() != command.id()
          || planned.threadId() != command.threadId()
          || !planned.payload().equals(command.payload())
          || !planned.clientCommandId().equals(command.clientCommandId())
          || planned.state() != command.state()) {
        return false;
      }
    }
    return withinCutoff == plan.plannedCommands().size();
  }

  /** 在冻结 request 的 tool bindings 中按 descriptor name 匹配 call name。 */
  private static ToolBinding bindingFor(List<ToolBinding> bindings, String toolName) {
    for (ToolBinding binding : bindings) {
      if (binding.descriptor().name().equals(toolName)) {
        return binding;
      }
    }
    return null;
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
   * 内部回滚信号：final claim fence 丢失时抛出，使当前事务完整回滚（零 durable mutation），再由 {@link #process} 捕获并映射为
   * LOST_OWNERSHIP。除 {@link #process} 外不得被捕获。
   */
  private static final class ClaimLostSignal extends RuntimeException {
    private ClaimLostSignal() {
      super("claimed work lost at final fence", null, false, false);
    }
  }

  /** 单步短事务的确定性结果。 */
  private sealed interface LoopStep
      permits LoopStep.Continue,
          LoopStep.Plan,
          LoopStep.Suspend,
          LoopStep.Quiescent,
          LoopStep.Lost {
    record Continue() implements LoopStep {}

    record Plan(TurnPlan plan) implements LoopStep {}

    record Suspend() implements LoopStep {}

    record Quiescent() implements LoopStep {}

    record Lost() implements LoopStep {}
  }

  /** resolve + 提交的组合结果。 */
  private enum ResolveOutcome {
    RESOLVED_COMMITTED,
    REJECTED_COMMITTED,
    RESCHEDULED,
    LOST
  }

  /** 提交事务的组合结果。 */
  private enum CommitOutcome {
    RESOLVED,
    REJECTED,
    LOST
  }
}
