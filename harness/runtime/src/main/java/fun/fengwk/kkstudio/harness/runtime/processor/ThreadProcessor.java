package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
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
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Target Thread processor：消费 dispatcher 已 claim 的 THREAD Work，以固定优先级驱动 Thread 的完整 Agent Loop。
 *
 * <p>固定优先级（单次 claim 的有界步骤循环，每步一个短事务）：(a) 应用当前 branch 上 terminal 且未挂 result Entry 的
 * ModelInvocation（仅当 Thread head 恰为 basis 时 applicable）；(b) 当前 head 恰为本 Thread ModelInvocation 产出 的
 * Assistant Entry、且其 Tool siblings 全部 terminal 且全部未挂 result 时按 ordinal 原子应用；(c) 当前 applicable
 * Model/Tool invocation 非 terminal 时完成 claim 并返回 SUSPENDED；(d) 无 open Turn 且 head 为
 * continueModel=true 的 TURN_END 时启动 continuation；(e) queued USER/CUSTOM 存在时启动 INPUT Turn；(f) 否则完成
 * claim 返回 QUIESCENT。旧 / 历史 open Turn 只在启动新 INPUT Turn 时被 normalization，绝不恢复 / 复用。
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
 * TURN_END(continueModel=false)，有 ToolCall 时按 response ordinal 创建全部 READY ToolInvocation（call name
 * 匹配 冻结 request 的 tool binding）并请求全部 TOOL Work、 不写 TURN_END；FAILED / CANCELLED / UNKNOWN 追加
 * AssistantError 与 FAILED TURN_END。Tool sibling 应用绝不部分 apply：数量 / ordinal 前缀 / ownership / 全部
 * terminal 且全部未挂 result 任一违反即抛错回滚。每次原子应用 Thread head/revision 只 +1；Model / Tool processor 不写
 * Entry/head。
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

  public ThreadProcessor(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isPresent()) {
      Entry turn = openTurn.get();
      // unlocked 读：决策阶段不产生任何 Model/Tool 锁。
      ModelInvocation peek = tx.findModelInvocationByTurn(thread.id(), turn.id()).orElse(null);
      // (a) terminal 未挂结果 Model：仅当当前 head 恰为 basis 才 applicable。
      if (peek != null
          && peek.resultEntryId() == null
          && peek.status().isTerminal()
          && thread.headEntryId() == peek.basisHeadEntryId()) {
        ModelInvocation model = tx.lockModelInvocation(peek.id()).orElse(null);
        if (model == null) {
          return new LoopStep.Lost();
        }
        return applyModel(tx, claim, thread, path, turn, model, now);
      }
      // (b) 非 terminal Model blocker：head == basis；Work-only，不锁 Model/Tool。
      if (peek != null
          && !peek.status().isTerminal()
          && thread.headEntryId() == peek.basisHeadEntryId()) {
        if (tx.lockClaimedWork(claim, now).isEmpty()) {
          return new LoopStep.Lost();
        }
        tx.completeWork(claim, now);
        return new LoopStep.Suspend();
      }
      // (c) Tool sibling：仅当 head 恰为本 Thread Model 产出的 Assistant Entry；unlocked 分类，真实 apply 才取锁。
      LoopStep toolStep = toolSiblingStep(tx, claim, thread, path, turn, peek, now);
      if (toolStep != null) {
        return toolStep;
      }
      // 历史 / 非 applicable open Turn：全程未锁 Model/Tool，落到下方 queued 快照与 INPUT normalization。
    } else if (path.head().payload() instanceof TurnEndPayload end && end.continueModel()) {
      return planStep(tx, claim, thread, path, TurnStartReason.CONTINUATION, now);
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
      return planStep(tx, claim, thread, path, TurnStartReason.INPUT, now);
    }
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      return new LoopStep.Lost();
    }
    tx.completeWork(claim, now);
    return new LoopStep.Quiescent();
  }

  /**
   * Tool sibling 分类与执行：head 必须恰为本 Thread Model 产出的 Assistant Entry。分类阶段只用 unlocked 读，且先做无锁一致性 校验：产出
   * Model 必须 SUCCEEDED 且携带 result，response toolCalls 必须与 assistant Message ToolCall contents 按序
   * 逐字段一致（id/name/argumentsJson），随后校验 assistant tool call 数量 / sibling 连续 ordinal / 全部归本 Model 所有
   * —— 任一违反即抛错回滚（非 SUCCEEDED / 结果不一致绝不静默历史化或降级为 blocker）；随后按状态分类 —— 存在已挂 result 但 并非全部 terminal+已挂
   * 是不变量违反抛错；全部 terminal+全部已挂 视为历史（结果在另一 descendant，返回 null 交 INPUT normalization，不锁
   * Model/Tool）；全部未挂载且存在非 terminal 时 Work-only blocker；全部 terminal 且全部未挂载 时才按 Thread -&gt; Model
   * -&gt; Tool -&gt; Work 取锁原子应用；SUCCEEDED 无 tool call 且无 sibling 的合法历史 assistant 返回 null。
   */
  private LoopStep toolSiblingStep(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      Entry turn,
      ModelInvocation peek,
      Instant now) {
    if (peek == null
        || peek.resultEntryId() == null
        || thread.headEntryId() != peek.resultEntryId()) {
      return null;
    }
    Entry assistant = path.head();
    if (!(assistant.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      return null;
    }
    if (peek.status() != ModelInvocationStatus.SUCCEEDED) {
      throw new IllegalStateException(
          "model "
              + peek.id()
              + " with status "
              + peek.status()
              + " must not attach an assistant message entry "
              + assistant.id());
    }
    if (peek.result() == null) {
      throw new IllegalStateException(
          "succeeded model "
              + peek.id()
              + " must carry a result for assistant entry "
              + assistant.id());
    }
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (var content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    if (!responseToolCallsMatch(peek.result(), calls)) {
      throw new IllegalStateException(
          "model "
              + peek.id()
              + " result tool calls must match the assistant message tool calls of entry "
              + assistant.id());
    }
    List<ToolInvocation> siblings = tx.loadToolInvocationsByAssistantEntryId(assistant.id());
    if (calls.isEmpty() && siblings.isEmpty()) {
      // 合法历史：assistant 无 tool call（含 attached 无调用结果），交 normalization。
      return null;
    }
    if (calls.size() != siblings.size()) {
      throw new IllegalStateException(
          "tool sibling count "
              + siblings.size()
              + " must match the assistant tool calls "
              + calls.size()
              + " of entry "
              + assistant.id());
    }
    for (int i = 0; i < siblings.size(); i++) {
      ToolInvocation sibling = siblings.get(i);
      if (sibling.ordinal() != i) {
        throw new IllegalStateException(
            "tool siblings must be a contiguous ordinal prefix of entry " + assistant.id());
      }
      if (sibling.modelInvocationId() != peek.id()) {
        throw new IllegalStateException(
            "tool siblings of entry " + assistant.id() + " must be owned by model " + peek.id());
      }
    }
    boolean allTerminal = true;
    boolean anyUnattached = false;
    boolean anyAttached = false;
    for (ToolInvocation sibling : siblings) {
      if (!sibling.status().isTerminal()) {
        allTerminal = false;
      } else if (sibling.resultEntryId() == null) {
        anyUnattached = true;
      } else {
        anyAttached = true;
      }
    }
    if (anyAttached && (!allTerminal || anyUnattached)) {
      throw new IllegalStateException(
          "tool siblings of entry "
              + assistant.id()
              + " must be all terminal and attached or all unattached");
    }
    if (anyAttached) {
      // 全部 terminal 且全部已挂结果：结果位于另一 descendant，历史，交 normalization（不锁 Model/Tool）。
      return null;
    }
    if (!allTerminal) {
      // 全部未挂载且存在非 terminal：blocker，Work-only。
      if (tx.lockClaimedWork(claim, now).isEmpty()) {
        return new LoopStep.Lost();
      }
      tx.completeWork(claim, now);
      return new LoopStep.Suspend();
    }
    ModelInvocation model = tx.lockModelInvocation(peek.id()).orElse(null);
    if (model == null) {
      return new LoopStep.Lost();
    }
    List<ToolInvocation> lockedSiblings = tx.lockToolInvocationsByAssistantEntryId(assistant.id());
    return applyToolBatch(
        tx, claim, thread, path, turn, model, assistant, calls, lockedSiblings, now);
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
      Entry turn,
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
    long assistantEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            assistantEntryId,
            sessionId,
            parentId,
            succeeded
                ? payloadMapper.assistantPayload(response)
                : payloadMapper.assistantErrorPayload(model.error()),
            now));
    tx.updateModelInvocation(model.attachResultEntry(assistantEntryId, now));
    long head = assistantEntryId;
    List<ToolInvocation> invocations = List.of();
    if (succeeded && response.toolCalls().isEmpty()) {
      long turnEndId = tx.nextId();
      tx.insertEntry(
          new Entry(
              turnEndId,
              sessionId,
              assistantEntryId,
              new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, false, null, null),
              now));
      head = turnEndId;
    } else if (succeeded) {
      List<ProviderToolCall> calls = response.toolCalls();
      List<ToolInvocation> materialized = new ArrayList<>(calls.size());
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
        materialized.add(
            new ToolInvocation(
                toolId,
                model.id(),
                assistantEntryId,
                ordinal,
                new ToolInvocationRequest(
                    new ToolCall(call.id(), call.name(), call.argumentsJson()), binding),
                ToolInvocationStatus.READY,
                0,
                null,
                null,
                null,
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
              assistantEntryId,
              new TurnEndPayload(
                  turn.id(), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
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
        tx.requestWork(new WorkTarget(WorkTargetType.TOOL, invocation.id()), now);
      }
    }
    return new LoopStep.Continue();
  }

  /**
   * Tool sibling 原子应用：全部 terminal 才执行，按 ordinal 追加全部 ToolResult + COMPLETED
   * TURN_END(continueModel=true)。数量 / ordinal 前缀 / ownership / terminal / unattached 任一违反即抛错回滚； 低序
   * mutation 完成后最后执行 claimed THREAD Work fence。
   */
  private LoopStep applyToolBatch(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      EntryPath path,
      Entry turn,
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
      long entryId = tx.nextId();
      tx.insertEntry(
          new Entry(entryId, sessionId, parentId, payloadMapper.toolResultPayload(sibling), now));
      updated.add(sibling.attachResultEntry(entryId, now));
      parentId = entryId;
    }
    long turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            sessionId,
            parentId,
            new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, true, null, null),
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
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    if (reason == TurnStartReason.CONTINUATION) {
      if (path.openTurnStart().isPresent()
          || !(path.head().payload() instanceof TurnEndPayload end && end.continueModel())) {
        return new LoopStep.Continue();
      }
    } else {
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
        planBuilder.build(thread.id(), path, thread.yoloEnabled(), reason, queued, tx::nextId, now);
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
      result = resolver.resolve(plan.threadId(), plan.candidatePath(), plan.finalYoloEnabled());
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

  /** response toolCalls 与 assistant Message ToolCall contents 按序逐字段一致（id/name/argumentsJson）。 */
  private static boolean responseToolCallsMatch(
      ProviderResponse response, List<ToolCallMessageContent> calls) {
    List<ProviderToolCall> responseCalls = response.toolCalls();
    if (responseCalls.size() != calls.size()) {
      return false;
    }
    for (int i = 0; i < calls.size(); i++) {
      ProviderToolCall call = responseCalls.get(i);
      ToolCallMessageContent content = calls.get(i);
      if (!call.id().equals(content.toolCallId())
          || !call.name().equals(content.toolName())
          || !call.argumentsJson().equals(content.argumentsJson())) {
        return false;
      }
    }
    return true;
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
