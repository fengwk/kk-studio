package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionResultEvaluator;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ResolvedRequestValidator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
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
 * <p>Turn 启动采用 speculative plan：短事务锁 Thread、读取 queued Command 快照与 cutoff、校验 claim（并对近过期 lease 做
 * {@link ProcessorLeaseSupport#ensureLeaseMargin} 保证首次 Resolver heartbeat 前不会过期）、分配 candidate Entry
 * ID 并构造完整合法 candidate EntryPath（不写任何持久化状态）；事务外调用 {@link TurnResolver}（期间由本地 {@link WorkHeartbeat}
 * 维持 lease）；第二短事务以 source head / cutoff 内 Command 精确快照 / claim ownership 做 CAS，一次性原子提交 TURN_START +
 * Message + Command markers + Thread 更新 + ModelInvocation/MODEL Work（resolved）或 AssistantError +
 * FAILED TURN_END（rejected）。Resolved 请求在提交前先经 {@link ResolvedRequestValidator} 按 candidate branch
 * 事实（route / model / variant / tools / compaction）做机械一致性校验，不一致即抛错且零持久化状态写入（绝不转 typed rejection）。任何
 * CAS / claim 损失一律抛内部 {@link ClaimLostSignal} 使事务完整回滚（零部分写入），由 {@link #process} 映射为
 * LOST_OWNERSHIP；Resolver 异常 / null / heartbeat 调度失败按单一正失败延迟 reschedule，绝不静默丢弃 Work。duplicate /
 * stale THREAD claim 是 no-op。
 *
 * <p>锁序与最终所有权围栏（final fence）：Model terminal apply / Tool sibling batch / resolve commit
 * 都在同一事务内先完成全部低序 写入（Thread -&gt; Commands -&gt; ModelInvocation -&gt; ToolInvocation
 * siblings），claimed THREAD Work 的最终所有权围栏最后执行；围栏失败抛出内部 {@link ClaimLostSignal} 使事务完整回滚，再由 {@link
 * #process} 映射为 LOST_OWNERSHIP，绝不存在带持久化变更的 LOST 提交。commit 与 applyModel 在同层 Work 中按 (type, id)
 * 升序请求（先 THREAD wake 再 MODEL / TOOL Work）。历史 / 非 applicable open Turn（head 不在 applicable
 * 位置）在分类阶段只使用 unlocked 读，绝不先锁 Model/Tool 再落到 Commands / INPUT normalization。
 *
 * <p>每次成功 action 都在同一事务 complete 当前 THREAD claim；下一 action 已确定时先 {@code requestWork(THREAD)} 再
 * complete，依赖 wakeVersion 保留新 wake。Model terminal apply 按 planner 决策落地：active Tool phase 仅为 READY
 * 槽位请求 TOOL Work（全部 immediate terminal 则请求 THREAD 让 batch 分下一 claim 经 ToolTerminalPending 应用）；
 * closed turn（COMPLETE 无 calls / LENGTH 无 calls / FILTERED / terminal failure / cancel / unknown /
 * compaction 关闭结果）在同一事务追加 TURN_END 与严格物化校验（attach-then-delete）并物理删除 ModelInvocation，且仅在已有 queued
 * user demand、HISTORY / OVERFLOW obligation、fallback 或 hard overflow 已确定时请求 THREAD；完全结束的 idle run
 * 不因 soft threshold 自唤醒。失败 / 停止 / complete COMPACTION 由 planner 判定不 spin。 Tool sibling batch 追加
 * outcome 后追加 continueModel=true TURN_END、同事务删除 children+parent，固定先请求 THREAD 再 complete，下一 claim 才做
 * continuation。resolve commit 的 resolved 只请求 MODEL Work，绝不因 deferred messages 制造无意义 THREAD claim
 * （terminal apply 会按 queued 快照重建 wake）；rejected 在保留 deferred messages 或闭合即出现 compaction action 时先请求
 * THREAD 再 complete。
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

  private final HarnessStore store;
  private final TurnResolver resolver;
  private final ThreadProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;
  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();
  private final TurnPlanBuilder planBuilder = new TurnPlanBuilder();
  private final ClaimAdmissionGuard admissionGuard = new ClaimAdmissionGuard();
  private final ThreadContextProbe threadContextProbe = new ThreadContextProbe();
  private final ModelResponsePlanner responsePlanner = new ModelResponsePlanner();
  private final AutomaticCompactionPlanner automaticCompactionPlanner =
      new AutomaticCompactionPlanner();

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
    // 唯一的 live/historical 适用性来源：不变量被破坏的形状由分类器以 ISE 拒绝，绝不降级为业务上下文。
    ThreadContext context = threadContextProbe.probe(tx, thread, path);
    return switch (context) {
      case ThreadContext.ModelTerminalPending pending -> {
        // 锁序 Thread -> Commands -> Model：判断 pre-existing queued message 必须在 lock Model 前加载。
        boolean hasQueuedMessage = hasQueuedUserMessage(tx.loadQueuedCommands(thread.id()));
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
            automaticCompactionPlanner.plan(
                thread, path, config.compactionProvider().compactionConfig(), true);
        if (preparation != null) {
          yield planStep(tx, claim, thread, path, TurnStartReason.COMPACTION, preparation, now);
        }
        yield planStep(tx, claim, thread, path, TurnStartReason.CONTINUATION, now);
      }
      case ThreadContext.IdleOrHistorical ignored -> {
        List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
        boolean userDemand = hasQueuedUserMessage(queued);
        // owned HISTORY / fallback / hard overflow 优先；idle soft threshold 只有真实 user demand 存在时才启动。
        CompactionPreparation preparation =
            automaticCompactionPlanner.plan(
                thread, path, config.compactionProvider().compactionConfig(), userDemand);
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

  /** queued 快照中是否存在真实 user-like 输入；SYSTEM steering 只等待下一 INPUT/CONTINUATION，不独立启动 Turn。 */
  private static boolean hasQueuedUserMessage(List<ThreadCommand> queued) {
    for (ThreadCommand command : queued) {
      if (command.payload() instanceof UserMessageCommandPayload) {
        return true;
      }
      if (command.payload() instanceof CustomMessageCommandPayload custom
          && custom.message().role() == AgentMessageRole.USER) {
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
   * ToolTerminalPending 应用并反馈模型）；完全结束的 closed turn 在新 Entries 插入、Thread advanced 后，只在已有 queued user
   * demand 或 fallback/hard-overflow obligation 已确定时请求 THREAD。soft threshold 没有新 demand 时保持
   * idle；新命令在事务后到达会自行 requestWork。
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
    if (turnStart.compaction() != null) {
      applyCompactionModel(
          tx,
          claim,
          thread,
          path,
          model,
          turnStart.compaction(),
          mutationNow,
          now,
          hasQueuedMessage);
      return;
    }
    UUID sessionId = path.root().sessionId();
    UUID parentId =
        ModelAttemptFailureAppender.append(tx, sessionId, path.head().id(), model, false);
    boolean succeeded = model.status() == ModelInvocationStatus.SUCCEEDED;
    ProviderResponse response = model.result();
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            resultEntryId,
            sessionId,
            parentId,
            succeeded
                ? payloadMapper.assistantPayload(response, model.requestSpec().toolBindings())
                : payloadMapper.assistantErrorPayload(model.error(), modelAttemptSnapshot(model)),
            mutationNow));
    UUID head = resultEntryId;
    boolean toolPhase = false;
    List<ToolInvocation> invocations = List.of();
    if (succeeded) {
      // canonical response 已在 SUCCEEDED 前通过 validator；这里只按 planner 的纯决策落地。
      ModelResponsePlan plan = responsePlanner.plan(response, model.requestSpec().toolBindings());
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
          Map<ContributorStateKey, ContributorStateAccessMode> seenStateAccesses = new HashMap<>();
          for (int callIndex = 0; callIndex < batch.tools().size(); callIndex++) {
            ModelResponsePlan.ToolSlot slot = batch.tools().get(callIndex);
            ToolInvocationStatus status = slot.status();
            ToolInvocationError error = slot.error();
            if (status == ToolInvocationStatus.READY) {
              // READY 槽位的 binding 由 planner 保证非空；contributor sibling 状态冲突仍在 Thread 边界确定性拒绝。
              ToolInvocationError conflict =
                  siblingStateConflict(slot.binding().contributor(), seenStateAccesses);
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
                    callIndex,
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
      // 完全结束的 closed turn 只重建 queued user / fallback / hard-overflow obligation；soft threshold
      // 无新 demand 时不 self-wake。
      boolean compactionDue =
          automaticCompactionPlanner.plan(
                  advancedThread,
                  tx.loadEntryPath(head),
                  config.compactionProvider().compactionConfig(),
                  hasQueuedMessage)
              != null;
      requestThread = hasQueuedMessage || compactionDue;
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
          EnvironmentId environmentId =
              invocation.binding() == null || invocation.binding().environment() == null
                  ? null
                  : invocation.binding().environment().environmentId();
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

  /** 应用一个 terminal Compaction Model；摘要语义失败也关闭本 turn，让 reducer决定一次 fallback或停止。 */
  private void applyCompactionModel(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      CompactionStart start,
      Instant mutationNow,
      Instant workNow,
      boolean hasQueuedUserMessage) {
    UUID sessionId = path.root().sessionId();
    EntryPayload resultPayload;
    TurnEndOutcome outcome;
    boolean continueModel = false;
    if (model.status() != ModelInvocationStatus.SUCCEEDED) {
      resultPayload =
          payloadMapper.assistantErrorPayload(model.error(), modelAttemptSnapshot(model));
      outcome = TurnEndOutcome.FAILED;
    } else {
      resultPayload = CompactionResultEvaluator.evaluate(path, start, model.result());
      if (resultPayload instanceof CompactionPayload) {
        outcome = TurnEndOutcome.COMPLETED;
        continueModel =
            start.phase() == CompactionPhase.HISTORY
                || start.trigger() == CompactionTrigger.OVERFLOW
                || (start.trigger() == CompactionTrigger.THRESHOLD
                    && CompactionHistory.hasPendingOwnedContinuation(
                        thread, path, model.turnStartEntryId()));
      } else {
        outcome = TurnEndOutcome.FAILED;
      }
    }
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(resultEntryId, sessionId, path.head().id(), resultPayload, mutationNow));
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            sessionId,
            resultEntryId,
            new TurnEndPayload(
                model.turnStartEntryId(),
                outcome,
                continueModel,
                outcome == TurnEndOutcome.FAILED ? TurnEndReason.TURN_FAILED : null,
                null),
            mutationNow));
    tx.updateModelInvocation(model.attachResultEntry(resultEntryId, mutationNow));
    tx.deleteModelInvocation(model.id());
    ThreadState advanced = thread.advanceHead(turnEndId, mutationNow);
    tx.updateThread(advanced);
    boolean compactionDue =
        automaticCompactionPlanner.plan(
                advanced,
                tx.loadEntryPath(turnEndId),
                config.compactionProvider().compactionConfig(),
                hasQueuedUserMessage)
            != null;
    boolean mechanicalWake = outcome == TurnEndOutcome.COMPLETED && continueModel;
    if (tx.lockClaimedWork(claim, workNow).isEmpty()) {
      throw new ClaimLostSignal();
    }
    if (hasQueuedUserMessage || compactionDue || mechanicalWake) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), workNow);
    }
    tx.completeWork(claim, workNow);
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
    // 删除 parent 前的严格物化校验：attached Assistant/result 与已物化失败 attempt 前缀必须与 immutable 事实一致。
    ModelAttemptMaterialization.validateAttached(model, path);
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            now, thread.updatedAt(), path.head().createdAt(), model.updatedAt());
    for (ToolInvocation sibling : siblings) {
      mutationNow = HarnessStoreTime.notBefore(mutationNow, sibling.updatedAt());
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
    Instant planNow = HarnessStoreTime.notBefore(now, thread.updatedAt(), path.head().createdAt());
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
      ResolvedRequestValidator.validate(plan.candidatePath(), plan.preparation(), resolved);
    }
    commit(claim, plan, result);
    return ThreadProcessResult.COMPLETED;
  }

  /**
   * 在第二事务中基于重新锁定的 Thread 重验 head、命令快照与 Claim，按 Thread -&gt; Commands -&gt; Model -&gt; Work
   * 锁序原子提交；最终 Claim 围栏失败会回滚全部变更。
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
    Instant mutationNow = HarnessStoreTime.notBefore(now, thread.updatedAt());
    for (Entry entry : plan.candidateEntries()) {
      mutationNow = HarnessStoreTime.notBefore(mutationNow, entry.createdAt());
    }
    Integer contextWindow =
        result instanceof TurnResolver.Resolved resolved ? resolved.contextWindow() : null;
    Integer maxOutputTokens =
        result instanceof TurnResolver.Resolved resolved ? resolved.maxOutputTokens() : null;
    // 低序 mutation（Entries / Commands / Thread / ModelInvocation）先完成。
    for (Entry entry : plan.candidateEntries()) {
      tx.insertEntry(
          withCreatedAt(
              withResolvedTurnStart(entry, plan, contextWindow, maxOutputTokens), mutationNow));
    }
    List<ThreadCommand> consumed = new ArrayList<>(plan.consumedCommands().size());
    for (ThreadCommand command : plan.consumedCommands()) {
      consumed.add(command.markApplied(plan.turnStartEntryId()));
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
      // Rejected turn 本身没有 resolved budgets；仅 deferred demand 或更早的 fallback/hard-overflow 事实可重建
      // wake。
      boolean deferredUserDemand = plan.hasDeferredUserMessages();
      boolean compactionDue =
          automaticCompactionPlanner.plan(
                  advanced,
                  tx.loadEntryPath(turnEndId),
                  config.compactionProvider().compactionConfig(),
                  deferredUserDemand)
              != null;
      if (tx.lockClaimedWork(claim, now).isEmpty()) {
        throw new ClaimLostSignal();
      }
      // rejected：保留 deferred user 或已确定 compaction obligation 时先请求 THREAD 再 complete。
      if (deferredUserDemand || compactionDue) {
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

  /** 第二阶段 commit 在插入前补齐 Resolver 成功时的 contextWindow/maxOutputTokens；rejected 保持 null。 */
  static Entry withResolvedTurnStart(
      Entry entry, TurnPlan plan, Integer contextWindow, Integer maxOutputTokens) {
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
            start.compaction()),
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
