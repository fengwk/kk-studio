package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionChildSpawn;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.CompactionResolution;
import fun.fengwk.kkstudio.harness.runtime.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Target Thread 手动压缩控制面：处理 availability 查询与 compactThread 提交。
 *
 * <p>执行顺序：单 store transaction 内按 Session KEY SHARE -&gt; Thread 顺序加锁，校验 expected version /
 * availability / boundary，构造 candidate COMPACTION TURN_START，随后在同一事务内同步调用 {@link
 * AgentDriver}，最后原子提交 COMPACTION Turn 与子执行 Work（或失败终态）。expectedVersion 仍是对外 CAS：version 不匹配直接返回
 * typed conflict； 不存在第二阶段 head / Command snapshot CAS，也不存在事务外 resolve。失败与取消保持与自动压缩一致的单一事实源。
 */
final class ManualCompactionControl {

  private final HarnessStore store;
  private final AgentDriver driver;
  private final CompactionConfigProvider compactionConfigProvider;
  private final ThreadContextProbe threadContextProbe;

  ManualCompactionControl(
      HarnessStore store, AgentDriver driver, CompactionConfigProvider compactionConfigProvider) {
    this.store = Objects.requireNonNull(store, "store");
    this.driver = Objects.requireNonNull(driver, "driver");
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
    this.threadContextProbe = new ThreadContextProbe(driver);
  }

  ManualCompactionAvailability manualCompactionAvailability(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return store.transaction(
        tx -> {
          ThreadState thread = lockThreadWithSession(tx, threadId);
          EntryPath path = tx.loadEntryPath(thread.headEntryId());
          return manualDecision(tx, thread, path).availability();
        });
  }

  CompactThreadResult compactThread(CompactThreadCommand command) {
    Objects.requireNonNull(command, "command");
    // 版本校验、candidate 构造、同库只读 resolve 与 COMPACTION 写入必须在同一个短事务内完成：要么整体提交，要么整体回滚。
    return store.transaction(
        tx -> {
          ManualPlan manualPlan = planManual(tx, command);
          TurnResolution result =
              driver.resolveTurn(
                  tx, manualPlan.thread(), manualPlan.candidatePath(), manualPlan.preparation());
          if (result == null) {
            throw new IllegalStateException("turn resolver returned null for manual compaction");
          }
          return commitManual(tx, manualPlan, result);
        });
  }

  /** 在当前事务中按 Session KEY SHARE -> Thread 锁序校验版本与可用性，并构造仅存在于内存的手动压缩 candidate。 */
  private ManualPlan planManual(HarnessStore.Transaction tx, CompactThreadCommand command) {
    ThreadState thread = lockThreadWithSession(tx, command.threadId());
    if (thread.version() != command.expectedVersion()) {
      throw staleManualVersion(command, thread);
    }
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    ManualDecision decision = manualDecision(tx, thread, path);
    if (!decision.availability().available()) {
      throw manualUnavailable(thread, decision.availability().disabledReason());
    }
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    Instant now = tx.now();
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
                decision.preparation().pendingStart(),
                null),
            now);
    List<Entry> candidateEntries = new ArrayList<>(path.entries());
    candidateEntries.add(candidateEntry);
    EntryPath candidatePath = new EntryPath(candidateEntries);
    return new ManualPlan(
        thread,
        path.root().sessionId(),
        candidatePath,
        turnStartEntryId,
        candidateEntry,
        path.head().id(),
        decision.preparation(),
        hasUserDemand(queued));
  }

  /** 沿当前分支回溯带上下文预算的已关闭 Turn，并依次检查所有权、模型与 token 阈值。 */
  private ManualDecision manualDecision(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    // STOPPED 是持久执行事实：历史仍然可读，但绝不能在停止的 Thread 上启动新的压缩回合或 resolve。
    if (thread.executionControl().isStopped()) {
      return ManualDecision.disabled(ManualCompactionAvailability.DisabledReason.THREAD_STOPPED);
    }
    CompactionConfig compactionConfig = compactionConfigProvider.compactionConfig();
    ThreadContext context = threadContextProbe.probe(tx, thread, path);
    if (!(context instanceof ThreadContext.IdleOrHistorical)
        || path.openTurnStart().isPresent()
        || !tx.loadIncompleteJoins(thread.id()).isEmpty()
        // 手动入口使用与自动压缩相同的约束 guard：创建约束不允许压缩的 Thread（压缩子自身及其后代）一律不可手动压缩，
        // 不依赖上层调用方（Web root guard）拦截，runtime primitive 自身绝不递归创建压缩子。
        || !thread.constraints().allowAutoCompaction()
        || driver.prepareAutomaticCompaction(thread, path, compactionConfig, false) != null) {
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

  /** 在当前已锁定事务中原子提交压缩 Turn 及其 Model Work 或失败终态；不再重验 version / head / Command 快照。 */
  private CompactThreadResult commitManual(
      HarnessStore.Transaction tx, ManualPlan plan, TurnResolution result) {
    Instant now = tx.now();
    ThreadState thread = plan.thread();
    if (result instanceof TurnResolution.Compaction compactionTurn) {
      CompactionResolution compactionResolved = compactionTurn.resolution();
      CompactionStart frozenStart =
          CompactionChildSpawn.start(tx, thread, compactionResolved, plan.preparation());
      TurnStartPayload startPayload = (TurnStartPayload) plan.candidateTurnStartEntry().payload();
      TurnStartPayload resolvedPayload =
          new TurnStartPayload(
              startPayload.reason(),
              startPayload.settings(),
              startPayload.ownerThreadId(),
              compactionResolved.contextWindow(),
              compactionResolved.maxOutputTokens(),
              frozenStart,
              null);
      tx.insertEntry(
          new Entry(
              plan.turnStartEntryId(),
              plan.sessionId(),
              plan.sourceHeadEntryId(),
              resolvedPayload,
              now));
      ThreadState advanced = thread.advanceHead(plan.turnStartEntryId(), now);
      tx.updateThread(advanced);
      return new CompactThreadResult(
          advanced, plan.turnStartEntryId(), frozenStart.childThreadId());
    }
    TurnResolution.Rejected rejected = (TurnResolution.Rejected) result;
    tx.insertEntry(
        new Entry(
            plan.turnStartEntryId(),
            plan.sessionId(),
            plan.sourceHeadEntryId(),
            plan.candidateTurnStartEntry().payload(),
            now));
    UUID errorEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            errorEntryId,
            plan.sessionId(),
            plan.turnStartEntryId(),
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
    ThreadState advanced = thread.advanceHead(turnEndId, now);
    tx.updateThread(advanced);
    if (plan.hasDeferredUserMessages()) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    return new CompactThreadResult(advanced, plan.turnStartEntryId(), null);
  }

  /** 按 Session KEY SHARE -&gt; Thread FOR UPDATE 获取一致 Thread，避免后续 Entry 外键锁与 Session 深删除形成逆序。 */
  private static ThreadState lockThreadWithSession(HarnessStore.Transaction tx, UUID threadId) {
    ThreadTreeLocks.lockForThread(tx, threadId);
    ThreadState thread =
        tx.findThread(threadId)
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException("thread " + threadId + " does not exist"));
    // 树锁内 Thread 不会被删除，其 Session 由外键保证存在。
    tx.lockSessionForKeyShare(thread.sessionId()).orElseThrow();
    return tx.lockThread(threadId).orElseThrow();
  }

  /** queued 快照中是否存在消息或系统通知（两者都能触发下一轮 INPUT）；SET_* 不构成用户需求。 */
  private static boolean hasUserDemand(List<ThreadCommand> queued) {
    for (ThreadCommand command : queued) {
      if (command.type().isMessage() || command.type().isNotification()) {
        return true;
      }
    }
    return false;
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
      ThreadState thread,
      UUID sessionId,
      EntryPath candidatePath,
      UUID turnStartEntryId,
      Entry candidateTurnStartEntry,
      UUID sourceHeadEntryId,
      CompactionPreparation preparation,
      boolean hasDeferredUserMessages) {
    private ManualPlan {
      Objects.requireNonNull(thread, "thread");
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(candidatePath, "candidatePath");
      Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
      Objects.requireNonNull(candidateTurnStartEntry, "candidateTurnStartEntry");
      Objects.requireNonNull(sourceHeadEntryId, "sourceHeadEntryId");
      Objects.requireNonNull(preparation, "preparation");
    }

    private UUID threadId() {
      return thread.id();
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
