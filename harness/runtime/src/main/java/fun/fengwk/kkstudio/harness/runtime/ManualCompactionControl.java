package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ResolvedRequestValidator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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

  private final HarnessStore store;
  private final Clock clock;
  private final TurnResolver resolver;
  private final CompactionConfigProvider compactionConfigProvider;
  private final ThreadContextProbe threadContextProbe = new ThreadContextProbe();
  private final AutomaticCompactionPlanner automaticPlanner = new AutomaticCompactionPlanner();

  ManualCompactionControl(
      HarnessStore store,
      Clock clock,
      TurnResolver resolver,
      CompactionConfigProvider compactionConfigProvider) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
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
            manualPlan.threadId(), manualPlan.candidatePath(), manualPlan.preparation());
    if (result == null) {
      throw new IllegalStateException("turn resolver returned null for manual compaction");
    }
    if (result instanceof TurnResolver.Resolved resolved) {
      ResolvedRequestValidator.validate(
          manualPlan.candidatePath(), manualPlan.preparation(), resolved);
    }
    return store.transaction(tx -> commitManual(tx, command, manualPlan, result));
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
    Instant now = clock.instant();
    Instant createdAt =
        HarnessStoreTime.notBefore(now, thread.updatedAt(), path.head().createdAt());
    UUID turnStartEntryId = tx.nextId();
    Entry candidateEntry =
        new Entry(
            turnStartEntryId,
            path.root().sessionId(),
            path.head().id(),
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                path.baseSettings(),
                thread.id(),
                null,
                null,
                decision.preparation().frozenStart()),
            createdAt);
    List<Entry> candidateEntries = new ArrayList<>(path.entries());
    candidateEntries.add(candidateEntry);
    EntryPath candidatePath = new EntryPath(candidateEntries);
    return new ManualPlan(
        thread.id(),
        path.root().sessionId(),
        candidatePath,
        turnStartEntryId,
        candidateEntry,
        path.head().id(),
        queued,
        decision.preparation(),
        hasUserDemand(queued));
  }

  /** 沿当前分支回溯带上下文预算的已关闭 Turn，并依次检查所有权、模型与 token 阈值。 */
  private ManualDecision manualDecision(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    CompactionConfig compactionConfig = compactionConfigProvider.compactionConfig();
    ThreadContext context = threadContextProbe.probe(tx, thread, path);
    if (!(context instanceof ThreadContext.IdleOrHistorical)
        || path.openTurnStart().isPresent()
        || automaticPlanner.plan(thread, path, compactionConfig, false) != null) {
      return ManualDecision.disabled(ManualCompactionAvailability.DisabledReason.THREAD_BUSY);
    }
    CompactionHistory.ClosedTurn candidate = CompactionHistory.latestClosedTurn(path);
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
          long minimum = compactionConfig.manualMinimum(start.contextWindow());
          if (projectedTokens < minimum) {
            return ManualDecision.disabled(
                ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM);
          }
          CompactionPreparation preparation =
              new CompactionPlanner(compactionConfig)
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
      candidate = CompactionHistory.previousClosedTurn(path, candidate);
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
      ManualPlan plan,
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
    if (!snapshotMatches(plan.queuedSnapshot(), queued)) {
      throw staleManualVersion(command, thread);
    }
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            now, thread.updatedAt(), plan.candidateTurnStartEntry().createdAt());
    if (result instanceof TurnResolver.Resolved resolved) {
      TurnStartPayload startPayload = (TurnStartPayload) plan.candidateTurnStartEntry().payload();
      TurnStartPayload resolvedPayload =
          new TurnStartPayload(
              startPayload.reason(),
              startPayload.settings(),
              startPayload.ownerThreadId(),
              resolved.contextWindow(),
              resolved.maxOutputTokens(),
              startPayload.compaction());
      tx.insertEntry(
          new Entry(
              plan.turnStartEntryId(),
              plan.sessionId(),
              plan.sourceHeadEntryId(),
              resolvedPayload,
              mutationNow));
      ThreadState advanced = thread.advanceHead(plan.turnStartEntryId(), mutationNow);
      tx.updateThread(advanced);
      UUID invocationId = tx.nextId();
      tx.insertModelInvocation(
          new ModelInvocation(
              invocationId,
              thread.id(),
              plan.turnStartEntryId(),
              plan.turnStartEntryId(),
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
    tx.insertEntry(
        new Entry(
            plan.turnStartEntryId(),
            plan.sessionId(),
            plan.sourceHeadEntryId(),
            plan.candidateTurnStartEntry().payload(),
            mutationNow));
    UUID errorEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            errorEntryId,
            plan.sessionId(),
            plan.turnStartEntryId(),
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

  private static boolean hasUserDemand(List<ThreadCommand> queued) {
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

  private static boolean snapshotMatches(List<ThreadCommand> expected, List<ThreadCommand> actual) {
    if (expected.size() != actual.size()) {
      return false;
    }
    for (int i = 0; i < actual.size(); i++) {
      if (!expected.get(i).equals(actual.get(i))) {
        return false;
      }
    }
    return true;
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

  private record ManualPlan(
      UUID threadId,
      UUID sessionId,
      EntryPath candidatePath,
      UUID turnStartEntryId,
      Entry candidateTurnStartEntry,
      UUID sourceHeadEntryId,
      List<ThreadCommand> queuedSnapshot,
      CompactionPreparation preparation,
      boolean hasDeferredUserMessages) {
    private ManualPlan {
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(candidatePath, "candidatePath");
      Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
      Objects.requireNonNull(candidateTurnStartEntry, "candidateTurnStartEntry");
      Objects.requireNonNull(sourceHeadEntryId, "sourceHeadEntryId");
      queuedSnapshot = List.copyOf(Objects.requireNonNull(queuedSnapshot, "queuedSnapshot"));
      Objects.requireNonNull(preparation, "preparation");
    }
  }

  private record ManualDecision(
      ManualCompactionAvailability availability, CompactionPreparation preparation) {
    private ManualDecision {
      Objects.requireNonNull(availability, "availability");
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
