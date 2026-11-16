package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionChildSpawn;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.driver.CompactionResolution;
import fun.fengwk.kkstudio.harness.spi.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turn 计划的原子提交：在已锁定 Session / Thread / Claim 的当前事务内按低序 mutation 写入 TURN_START + Message + Command
 * markers + Thread 更新 + Driver 私有 Turn 状态与 Work（accepted / compaction），或 AssistantError + FAILED
 * TURN_END（rejected）。
 *
 * <p>本类只负责把已解析的计划落库并完成 claim；解析、锁序与状态分类由 {@link ThreadProcessor} 承担。
 */
final class TurnCommitter {

  private final DriverRegistry driverRegistry;

  TurnCommitter(DriverRegistry driverRegistry) {
    this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
  }

  /**
   * 在调用方事务内提交一次已解析的 turn plan。
   *
   * <p>accepted 与 compaction 都推进 head 与输入水位并完成 claim；rejected 追加失败边界、结算 Join，仅在仍保留合法 deferred
   * messages 时先请求 THREAD wake 再 complete。
   */
  void commit(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      TurnPlan plan,
      TurnResolution resolution) {
    Instant now = tx.now();
    Integer contextWindow = null;
    Integer maxOutputTokens = null;
    CompactionStart compactionStart = null;
    TurnResolution.Accepted accepted = null;
    CompactionResolution compaction = null;
    if (resolution instanceof TurnResolution.Accepted acceptedTurn) {
      accepted = acceptedTurn;
      contextWindow = accepted.contextWindow();
      maxOutputTokens = accepted.maxOutputTokens();
    } else if (resolution instanceof TurnResolution.Compaction compactionTurn) {
      compaction = compactionTurn.resolution();
      contextWindow = compaction.contextWindow();
      maxOutputTokens = compaction.maxOutputTokens();
      compactionStart =
          CompactionChildSpawn.start(tx, thread, compaction, plan.preparation(), driverRegistry);
    }
    List<Entry> candidateEntries = plan.candidateEntries();
    if (accepted != null && accepted.systemChange() != null) {
      candidateEntries =
          insertSystemChange(tx, plan, candidateEntries, accepted.systemChange(), now);
    }
    for (Entry entry : candidateEntries) {
      tx.entries()
          .insert(
              withCreatedAt(
                  withResolvedTurnStart(
                      entry, plan, contextWindow, maxOutputTokens, compactionStart, accepted),
                  now));
    }
    markCommandsApplied(tx, plan);
    if (accepted != null) {
      ThreadState advanced = advanceCommittedThread(tx, thread, plan, now);
      accepted.install(tx, thread.id(), plan.turnStartEntryId(), plan.candidateHeadEntryId());
      WorkClaims.complete(tx, claim);
    } else if (compaction != null) {
      advanceCommittedThread(tx, thread, plan, now);
      WorkClaims.complete(tx, claim);
    } else {
      commitRejected(tx, claim, thread, plan, (TurnResolution.Rejected) resolution, now);
    }
  }

  /** 把每条已消费 Command 的物化坐标写回 command 行。 */
  private static void markCommandsApplied(HarnessStore.Transaction tx, TurnPlan plan) {
    List<ThreadCommand> consumed = new ArrayList<>(plan.consumedCommands().size());
    for (ThreadCommand command : plan.consumedCommands()) {
      consumed.add(command.markApplied(plan.appliedEntryId(command.sequence())));
    }
    tx.commands().update(consumed);
  }

  private static ThreadState advanceCommittedThread(
      HarnessStore.Transaction tx, ThreadState thread, TurnPlan plan, Instant now) {
    ThreadState advanced =
        thread.advanceHeadAndInputThroughSequence(
            plan.candidateHeadEntryId(), nextWatermark(plan, thread), now);
    tx.threads().update(advanced);
    return advanced;
  }

  /** rejected：AssistantError + FAILED TURN_END，并结算 Join。 */
  private static void commitRejected(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      TurnPlan plan,
      TurnResolution.Rejected rejected,
      Instant now) {
    UUID errorEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                errorEntryId,
                plan.sessionId(),
                plan.candidateHeadEntryId(),
                new AssistantErrorPayload(rejected.error(), null),
                now));
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
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
    boolean requestThread = plan.hasDeferredUserMessages();
    ThreadState advanced =
        thread.advanceHeadAndInputThroughSequence(turnEndId, nextWatermark(plan, thread), now);
    tx.threads().update(advanced);
    ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
        tx, advanced, turnEndId, null, now, false);
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    if (requestThread) {
      tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    tx.work().complete(claim);
  }

  /** INPUT 与分支内部指令推进输入水位；CONTINUATION / COMPACTION 不推进。 */
  private static long nextWatermark(TurnPlan plan, ThreadState thread) {
    if (plan.reason() == TurnStartReason.INPUT
        || plan.reason() == TurnStartReason.COMPACTION_BRANCH_INPUT) {
      return plan.cutoffSequence();
    }
    return thread.inputThroughSequence();
  }

  /** 在 TURN_START 前插入一条系统变更 Entry，并让它成为新 TURN_START 的父节点。 */
  private static List<Entry> insertSystemChange(
      HarnessStore.Transaction tx,
      TurnPlan plan,
      List<Entry> candidateEntries,
      SystemPayload systemChange,
      Instant now) {
    int turnStartIndex = -1;
    for (int i = 0; i < candidateEntries.size(); i++) {
      if (candidateEntries.get(i).id().equals(plan.turnStartEntryId())) {
        turnStartIndex = i;
        break;
      }
    }
    if (turnStartIndex < 0) {
      throw new IllegalStateException("candidate entries do not contain TURN_START");
    }
    Entry turnStart = candidateEntries.get(turnStartIndex);
    if (!(turnStart.payload() instanceof TurnStartPayload)) {
      throw new IllegalStateException("turnStartEntryId does not identify a TURN_START");
    }
    Entry systemEntry =
        new Entry(tx.nextId(), plan.sessionId(), turnStart.parentEntryId(), systemChange, now);
    List<Entry> result = new ArrayList<>(candidateEntries.size() + 1);
    result.addAll(candidateEntries.subList(0, turnStartIndex));
    result.add(systemEntry);
    result.add(
        new Entry(
            turnStart.id(),
            turnStart.sessionId(),
            systemEntry.id(),
            turnStart.payload(),
            turnStart.createdAt()));
    result.addAll(candidateEntries.subList(turnStartIndex + 1, candidateEntries.size()));
    return List.copyOf(result);
  }

  static Entry withCreatedAt(Entry entry, Instant createdAt) {
    return new Entry(
        entry.id(), entry.sessionId(), entry.parentEntryId(), entry.payload(), createdAt);
  }

  /**
   * 插入前补齐 Driver 解析成功时的 contextWindow/maxOutputTokens/compactionStart；rejected 保持 null。Driver
   * 实际执行的模型不同于 Thread 设置时记为 fallbackModel。
   */
  static Entry withResolvedTurnStart(
      Entry entry,
      TurnPlan plan,
      Integer contextWindow,
      Integer maxOutputTokens,
      CompactionStart compactionStart,
      TurnResolution.Accepted accepted) {
    if (!entry.id().equals(plan.turnStartEntryId())
        || !(entry.payload() instanceof TurnStartPayload start)) {
      return entry;
    }
    ExecutionSelection executed = accepted == null ? null : accepted.executedSelection();
    ExecutionSelection selectedExecution = start.settings().execution();
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
            executed == null || selectedExecution == null || executed.equals(selectedExecution)
                ? null
                : executed,
            accepted == null ? null : accepted.capabilities()),
        entry.createdAt());
  }
}
