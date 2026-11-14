package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedExecutionSupport;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次已 claim MODEL Work 的短 prepare 事务：按 durable 状态复核所有权并决定启动、重排或终止。
 *
 * <p>锁序固定为 own Thread -&gt; ModelInvocation -&gt; claimed Work（同事务需要 THREAD Work 时先 request/upsert
 * 并锁定 THREAD Work）， 不取执行树锁、祖先锁或 Session 锁。只读状态判断与写入都发生在同一个事务内，因此 {@link Result} 的三态与写入结果一致。
 */
final class ModelClaimPreparer {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ModelGateway gateway;
  private final ModelProcessorConfig config;
  private final ClaimedExecutionSupport<ModelExecution> executions;

  ModelClaimPreparer(
      HarnessStore store,
      BuiltinStore builtinStore,
      ModelGateway gateway,
      ModelProcessorConfig config,
      ClaimedExecutionSupport<ModelExecution> executions) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = Objects.requireNonNull(builtinStore, "builtinStore");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.config = Objects.requireNonNull(config, "config");
    this.executions = Objects.requireNonNull(executions, "executions");
  }

  Result inTransaction(ClaimedWork claim, ProcessorLeaseConfig leaseConfig) {
    return store.transaction(
        core -> prepareLocked(core, builtinStore.in(core), claim, core.now(), leaseConfig));
  }

  /** 在事务内按 own Thread -&gt; Model -&gt; Work 锁序锁定并校验状态，不取树锁/祖先锁，依据 durable status 路由准备结果。 */
  private Result prepareLocked(
      HarnessStore.Transaction core,
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      Instant now,
      ProcessorLeaseConfig leaseConfig) {
    UUID invocationId = claim.target().id();
    ModelInvocation peek = tx.findModelInvocation(invocationId).orElse(null);
    if (peek == null) {
      return new Result.Lost();
    }
    ThreadState thread = tx.threads().lock(peek.threadId()).orElse(null);
    if (thread == null) {
      return new Result.Lost();
    }
    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
    if (model == null) {
      return new Result.Lost();
    }
    return switch (model.status()) {
      case READY -> prepareReady(core, tx, claim, thread, model, now, leaseConfig);
      case RUNNING -> recoverRunning(tx, claim, thread, model, now);
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> cleanupTerminal(tx, claim, thread, model, now);
    };
  }

  private Result prepareReady(
      HarnessStore.Transaction core,
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now,
      ProcessorLeaseConfig leaseConfig) {
    ModelGroupExecution group = model.requestSpec().groupExecution();
    ModelGroupProgress progress = model.groupProgress();
    int memberIndex = 0;
    if (group != null) {
      if (progress == null || progress.groupVersion() != group.groupVersion()) {
        throw new IllegalStateException("group invocation is missing matching durable progress");
      }
      memberIndex = firstAvailableMember(core, group, progress.memberIndex());
      if (memberIndex >= group.members().size()) {
        return terminateWithoutAvailableMember(tx, claim, thread, model, group, now);
      }
    }
    Optional<Work> claimed = tx.work().lockClaimed(claim);
    if (claimed.isEmpty()) {
      return new Result.Lost();
    }
    // 运行前确保 lease 有完整 margin：剩余不足以撑到首次 heartbeat 时立即 renew。
    executions.ensureLeaseMargin(tx, claim, leaseConfig);
    requireTurnStart(tx, model);
    // 摘要特有的完整性校验只属于「本线程当前直接持有 SETTLE_OWNER_TURN 票据」的执行；约束只读本行执行语义，绝不据此推断用途，
    // 否则普通子代理继承 allowAutoCompaction=false 时会被误当成摘要 Agent。票据查询只读本线程，不回溯父历史或祖先。
    boolean ownerSettledSummary =
        tx.joins().loadIncomplete(thread.id()).stream()
            .anyMatch(join -> join.delivery() == JoinDelivery.SETTLE_OWNER_TURN);
    if (group != null && memberIndex != progress.memberIndex()) {
      // 跳过冷却成员：新游标在运行前落库，执行身份与审计都来自该成员。
      progress =
          new ModelGroupProgress(progress.groupName(), progress.groupVersion(), memberIndex, 0);
      tx.updateModelInvocation(model.withGroupProgress(progress, now));
      tx.threads().update(thread.touchVersion(now));
    }
    return new Result.Dispatched(
        thread.id(),
        model.requestHeadEntryId(),
        model.attempt(),
        model.requestSpec(),
        progress,
        ownerSettledSummary);
  }

  /** 全部成员都在冷却：不伪装成上游调用错误，也不进入普通重试预算，直接以 NO_AVAILABLE_MODEL 终止。 */
  private Result terminateWithoutAvailableMember(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      ModelGroupExecution group,
      Instant now) {
    // THREAD Work 先于 MODEL Work 加锁。
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.updateModelInvocation(
        model.fail(
            new ModelInvocationError(
                ProviderErrorKind.NO_AVAILABLE_MODEL,
                "no available model in group: " + group.groupName()),
            now));
    tx.threads().update(thread.touchVersion(now));
    tx.work().complete(claim);
    return new Result.Terminated();
  }

  /** ModelInvocation 必须挂在真实 TURN_START 上：拒绝引用缺失或类型错误的 turn start。 */
  private static void requireTurnStart(BuiltinStore.Transaction tx, ModelInvocation model) {
    Entry turnStart =
        tx.entries()
            .find(model.turnStartEntryId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "model invocation "
                            + model.id()
                            + " references missing turn start "
                            + model.turnStartEntryId()));
    if (!(turnStart.payload() instanceof TurnStartPayload)) {
      throw new IllegalStateException(
          "model invocation " + model.id() + " turnStartEntryId does not reference a TURN_START");
    }
  }

  /** 从 {@code fromIndex} 起第一个当前不在冷却的成员下标；全部冷却时返回成员数。 */
  private int firstAvailableMember(
      HarnessStore.Transaction tx, ModelGroupExecution group, int fromIndex) {
    int index = fromIndex;
    while (index < group.members().size()
        && gateway.isGroupMemberCooling(
            tx,
            group.groupName(),
            group.groupVersion(),
            group.members().get(index).model().modelName(),
            group.members().get(index).variant().id())) {
      index++;
    }
    return index;
  }

  /**
   * 旧 lease 过期恢复：执行持有者已失效，但 RUNNING 已持久化，请求可能已经发出，不把它当成确定未执行。
   *
   * <p>RUNNING 的 attempt 已被消耗。允许重试时记录一条 retryable failed attempt 后回到 READY，并按策略延迟重排 MODEL Work（不
   * request THREAD wake，旧 partial 只作为尝试审计，绝不与新 attempt 的文本或 tool-call 拼接）；预算耗尽时以 FAILED 明确终止并唤醒
   * THREAD。 绝不重放 Provider，也不新建恢复专用预算或重置计数。
   */
  private Result recoverRunning(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    ModelGroupExecution group = model.requestSpec().groupExecution();
    ModelGroupProgress groupProgress = model.groupProgress();
    if (group != null
        && (groupProgress == null
            || groupProgress.groupVersion() != group.groupVersion()
            || groupProgress.memberIndex() >= group.members().size())) {
      throw new IllegalStateException("group invocation is missing matching durable progress");
    }
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT,
            "model work lease expired; provider outcome cannot be confirmed");
    // 组恢复只消耗当前成员的尝试预算，绝不因结果未知自动冻结或降级到下一成员。
    int budgetAttempt =
        group == null ? model.attempt() : Math.addExact(groupProgress.memberAttempts(), 1);
    InvocationRetryDecision decision =
        InvocationRetryDecision.decide(config.retryPolicyProvider().get(), true, budgetAttempt);
    if (decision.retry()) {
      return recoverAsReady(tx, claim, thread, model, group, groupProgress, error, now, decision);
    }
    return recoverAsTerminal(tx, claim, thread, model, group, error, now);
  }

  private Result recoverAsReady(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      ModelGroupExecution group,
      ModelGroupProgress groupProgress,
      ModelInvocationError error,
      Instant now,
      InvocationRetryDecision decision) {
    Instant retryAt = now.plus(decision.delay());
    ModelInvocation retried =
        model.retryReady(
            ModelAttemptFailures.fromCheckpoint(model, model.attempt(), error, now, retryAt), now);
    if (group != null) {
      retried = retried.withGroupProgress(groupProgress.retryCurrent(), now);
    }
    tx.updateModelInvocation(retried);
    tx.threads().update(thread.touchVersion(now));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().reschedule(claim, decision.delay());
    return new Result.Retry();
  }

  /** 预算耗尽：如实终止并唤醒 THREAD，不通过无限重试伪装持续推进。 */
  private Result recoverAsTerminal(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      ModelGroupExecution group,
      ModelInvocationError error,
      Instant now) {
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    // 组的恢复失败同样计入当前成员的失败审计；仍停留在原成员，且不冻结、不降级。
    tx.updateModelInvocation(
        group == null
            ? model.fail(error, now)
            : model.failAfterAttempt(
                error,
                ModelAttemptFailures.fromCheckpoint(model, model.attempt(), error, now, now),
                now));
    tx.threads().update(thread.touchVersion(now));
    tx.work().complete(claim);
    return new Result.Terminated();
  }

  /** terminal 行：resultEntryId 仍 null 时确保 THREAD Work 请求，然后 complete MODEL Work；不重复 bump version。 */
  private static Result cleanupTerminal(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    if (model.resultEntryId() == null) {
      // requestWork 同时 upsert 并锁定 THREAD Work；即使原行不存在，也能建立正确锁序。
      tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().complete(claim);
    return new Result.Terminated();
  }

  /** prepare 短事务的结论。 */
  sealed interface Result permits Result.Lost, Result.Dispatched, Result.Terminated, Result.Retry {

    record Lost() implements Result {}

    /** 失联恢复已按策略回到 READY 并重排 MODEL Work：不启动 Provider，不 request THREAD wake。 */
    record Retry() implements Result {}

    /**
     * 已冻结的 dispatch：{@code groupProgress} 是模型组本次运行的 durable 成员游标（单模型为 null）；{@code
     * ownerSettledSummary} 来自本线程当前有效未完成票据的 SETTLE_OWNER_TURN 交付方式，不查询祖先。
     */
    record Dispatched(
        UUID threadId,
        UUID requestHeadEntryId,
        int attempt,
        ModelRequestSpec request,
        ModelGroupProgress groupProgress,
        boolean ownerSettledSummary)
        implements Result {}

    record Terminated() implements Result {}
  }
}
