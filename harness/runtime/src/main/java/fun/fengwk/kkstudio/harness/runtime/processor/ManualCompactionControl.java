package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor.durableMutationTime;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor.latestClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor.previousClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor.snapshotMatches;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor.withCreatedAt;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor.withResolvedTurnStart;

import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor.ClosedTurn;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Target Thread 手动压缩控制面：处理 availability 查询与 compactThread 提交。
 *
 * <p>执行顺序严格保持：第一短事务 plan（锁定 Thread，校验 version/availability/boundary） -&gt; 事务外 resolve -&gt;
 * 机械一致性校验 -&gt; 第二短事务 commit（带 source head / Command 快照 CAS 原子提交 COMPACTION Turn 与 MODEL Work）。
 * 失败与取消保持与自动压缩一致的单一事实源。
 */
final class ManualCompactionControl {

  @FunctionalInterface
  interface ContextLoader {
    ThreadContext load(HarnessStore.Transaction tx, ThreadState thread, EntryPath path);
  }

  @FunctionalInterface
  interface CompactionPreparationProbe {
    CompactionPreparation probe(ThreadState thread, EntryPath path, boolean thresholdEligible);
  }

  private final HarnessStore store;
  private final TurnResolver resolver;
  private final ThreadProcessorConfig config;
  private final Clock clock;
  private final TurnPlanBuilder planBuilder;
  private final ContextLoader contextLoader;
  private final CompactionPreparationProbe compactionPreparationProbe;

  ManualCompactionControl(
      HarnessStore store,
      TurnResolver resolver,
      ThreadProcessorConfig config,
      Clock clock,
      TurnPlanBuilder planBuilder,
      ContextLoader contextLoader,
      CompactionPreparationProbe compactionPreparationProbe) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.planBuilder = Objects.requireNonNull(planBuilder, "planBuilder");
    this.contextLoader = Objects.requireNonNull(contextLoader, "contextLoader");
    this.compactionPreparationProbe =
        Objects.requireNonNull(compactionPreparationProbe, "compactionPreparationProbe");
  }

  ManualCompactionAvailability manualCompactionAvailability(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(threadId)
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + threadId + " does not exist"));
          EntryPath path = tx.loadEntryPath(thread.headEntryId());
          return manualDecision(tx, thread, path).availability();
        });
  }

  CompactThreadResult compactThread(CompactThreadCommand command) {
    Objects.requireNonNull(command, "command");
    ManualPlan manualPlan = store.transaction(tx -> planManual(tx, command));
    TurnResolver.Result result =
        resolver.resolve(
            manualPlan.plan().threadId(),
            manualPlan.plan().candidatePath(),
            manualPlan.plan().preparation());
    if (result == null) {
      throw new IllegalStateException("turn resolver returned null for manual compaction");
    }
    if (result instanceof TurnResolver.Resolved resolved) {
      ResolvedRequestValidator.validate(manualPlan.plan(), resolved);
    }
    return store.transaction(tx -> commitManual(tx, command, manualPlan.plan(), result));
  }

  /** 在首个事务中锁定 Thread、校验版本与可用性，并冻结供事务外解析的手动压缩规划。 */
  private ManualPlan planManual(HarnessStore.Transaction tx, CompactThreadCommand command) {
    ThreadState thread =
        tx.lockThread(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));
    if (thread.version() != command.expectedVersion()) {
      throw staleManualVersion(command, thread);
    }
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    ManualDecision decision = manualDecision(tx, thread, path);
    if (!decision.availability().available()) {
      throw manualUnavailable(thread, decision.availability().disabledReason());
    }
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    Instant now = durableMutationTime(clock.instant(), thread.updatedAt(), path.head().createdAt());
    TurnPlan plan =
        planBuilder.build(
            thread.id(),
            path,
            TurnStartReason.COMPACTION,
            queued,
            tx::nextId,
            now,
            decision.preparation());
    return new ManualPlan(plan);
  }

  /** 沿当前分支回溯带上下文预算的已关闭 Turn，并依次检查所有权、模型与 token 阈值。 */
  private ManualDecision manualDecision(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    ThreadContext context = contextLoader.load(tx, thread, path);
    if (!(context instanceof ThreadContext.IdleOrHistorical)
        || path.openTurnStart().isPresent()
        || compactionPreparationProbe.probe(thread, path, false) != null) {
      return ManualDecision.disabled(ManualCompactionAvailability.DisabledReason.THREAD_BUSY);
    }
    ClosedTurn candidate = latestClosedTurn(path, path.entries().size());
    boolean mismatchedModelSeen = false;
    while (candidate != null) {
      TurnStartPayload start = (TurnStartPayload) candidate.start().payload();
      if (!thread.id().equals(start.ownerThreadId())) {
        return ManualDecision.disabled(
            ManualCompactionAvailability.DisabledReason.OWNERSHIP_BARRIER);
      }
      if (start.contextWindow() != null && start.maxOutputTokens() != null) {
        var executionModel =
            start.compaction() == null
                ? start.settings().model()
                : start.compaction().executionModel();
        if (executionModel.equals(path.baseSettings().model())) {
          long projectedTokens = CompactionPlanner.estimateProjectionTokens(path);
          long minimum =
              config.compactionProvider().compactionConfig().manualMinimum(start.contextWindow());
          if (projectedTokens < minimum) {
            return ManualDecision.disabled(
                ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM);
          }
          CompactionPreparation preparation =
              compactionPlanner()
                  .prepare(path, CompactionTrigger.MANUAL, start.contextWindow())
                  .orElse(null);
          return preparation == null
              ? ManualDecision.disabled(
                  ManualCompactionAvailability.DisabledReason.NOTHING_TO_COMPACT)
              : ManualDecision.enabled(preparation);
        }
        if (start.compaction() == null) {
          mismatchedModelSeen = true;
        }
      }
      candidate = previousClosedTurn(path, candidate);
    }
    return ManualDecision.disabled(
        mismatchedModelSeen
            ? ManualCompactionAvailability.DisabledReason.MODEL_CHANGED
            : ManualCompactionAvailability.DisabledReason.NO_RESOLVED_CONTEXT);
  }

  /** 在第二事务中重验版本、head 与命令快照，再原子提交压缩 Turn 及其 Model Work 或失败终态。 */
  private CompactThreadResult commitManual(
      HarnessStore.Transaction tx,
      CompactThreadCommand command,
      TurnPlan plan,
      TurnResolver.Result result) {
    Instant now = clock.instant();
    ThreadState thread =
        tx.lockThread(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));
    if (thread.version() != command.expectedVersion()
        || !thread.headEntryId().equals(plan.sourceHeadEntryId())) {
      throw staleManualVersion(command, thread);
    }
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    if (!snapshotMatches(plan, queued)) {
      throw staleManualVersion(command, thread);
    }
    if (!plan.consumedCommands().isEmpty()) {
      throw new IllegalStateException("manual compaction must not consume commands");
    }
    Instant mutationNow = durableMutationTime(now, thread.updatedAt());
    for (Entry entry : plan.candidateEntries()) {
      mutationNow = durableMutationTime(mutationNow, entry.createdAt());
    }
    Integer contextWindow =
        result instanceof TurnResolver.Resolved resolved ? resolved.contextWindow() : null;
    Integer maxOutputTokens =
        result instanceof TurnResolver.Resolved resolved ? resolved.maxOutputTokens() : null;
    for (Entry entry : plan.candidateEntries()) {
      tx.insertEntry(
          withCreatedAt(
              withResolvedTurnStart(entry, plan, contextWindow, maxOutputTokens), mutationNow));
    }
    if (result instanceof TurnResolver.Resolved resolved) {
      ThreadState advanced = thread.advanceHead(plan.candidateHeadEntryId(), mutationNow);
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
      tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId), now);
      return new CompactThreadResult(advanced, plan.turnStartEntryId(), invocationId);
    }
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
    if (plan.hasDeferredUserMessages()) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    }
    return new CompactThreadResult(advanced, plan.turnStartEntryId(), null);
  }

  private CompactionPlanner compactionPlanner() {
    return new CompactionPlanner(config.compactionProvider().compactionConfig());
  }

  private static HarnessRuntimeConflictException staleManualVersion(
      CompactThreadCommand command, ThreadState thread) {
    return new HarnessRuntimeConflictException(
        HarnessRuntimeConflictException.Reason.STALE_VERSION,
        "thread "
            + thread.id()
            + " version "
            + thread.version()
            + " does not match expected "
            + command.expectedVersion());
  }

  private static HarnessRuntimeConflictException manualUnavailable(
      ThreadState thread, ManualCompactionAvailability.DisabledReason reason) {
    return new HarnessRuntimeConflictException(
        HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE,
        "thread " + thread.id() + " cannot be compacted manually: " + reason);
  }

  private record ManualPlan(TurnPlan plan) {
    private ManualPlan {
      plan = Objects.requireNonNull(plan, "plan");
    }
  }

  private record ManualDecision(
      ManualCompactionAvailability availability, CompactionPreparation preparation) {
    private ManualDecision {
      availability = Objects.requireNonNull(availability, "availability");
      if (availability.available() != (preparation != null)) {
        throw new IllegalArgumentException(
            "manual compaction preparation must be present iff availability is enabled");
      }
    }

    private static ManualDecision enabled(CompactionPreparation preparation) {
      return new ManualDecision(
          ManualCompactionAvailability.enabled(),
          Objects.requireNonNull(preparation, "preparation"));
    }

    private static ManualDecision disabled(ManualCompactionAvailability.DisabledReason reason) {
      return new ManualDecision(ManualCompactionAvailability.disabled(reason), null);
    }
  }
}
