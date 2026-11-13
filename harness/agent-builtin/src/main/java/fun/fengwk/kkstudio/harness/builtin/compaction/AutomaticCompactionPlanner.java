package fun.fengwk.kkstudio.harness.builtin.compaction;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.TokenEstimator;

import java.util.Objects;

/**
 * Builtin Driver 的自动压缩规划器：优先恢复硬溢出，再在 eligible 时判断软阈值。
 *
 * <p>策略属于 Builtin；Runtime 只通过 {@code AgentDriver#prepareAutomaticCompaction} 调用。每次决策使用同一个 {@link
 * CompactionConfig} 快照构建或驱动 {@link CompactionPlanner}，保证阈值判定与规划逻辑完全一致。
 */
public final class AutomaticCompactionPlanner {

  public AutomaticCompactionPlanner() {}

  /** 优先规划硬溢出恢复，再检查 eligible 的软阈值；无需自动压缩时返回 null。 */
  public CompactionPreparation plan(
      ThreadState thread, EntryPath path, CompactionConfig compaction, boolean thresholdEligible) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(compaction, "compaction");
    ExecutionSelection execution = path.baseSettings().execution();

    CompactionHistory.ClosedTurn latestTurn = CompactionHistory.latestClosedTurn(path);
    if (latestTurn == null) {
      return null;
    }
    Entry latestStart = latestTurn.start();
    TurnStartPayload latestStartPayload = (TurnStartPayload) latestStart.payload();
    CompactionPlanner planner = new CompactionPlanner(compaction);

    if (latestStartPayload.reason() == TurnStartReason.COMPACTION) {
      return null;
    }
    if (!thread.id().equals(latestStartPayload.ownerThreadId())) {
      return null;
    }
    Entry latestResultEntry = CompactionHistory.turnResultEntry(path, latestStart);
    if (CompactionHistory.isContextWall(latestStartPayload, latestResultEntry)) {
      if (latestStartPayload.contextWindow() == null) {
        return null;
      }
      if (CompactionHistory.isOverflowRecoveryRetry(path, latestTurn)) {
        return null;
      }
      return planner
          .prepare(path, CompactionTrigger.OVERFLOW, latestStartPayload.contextWindow())
          .orElse(null);
    }
    if (!thresholdEligible) {
      return null;
    }
    return thresholdPreparation(thread, path, latestTurn, compaction, execution, planner);
  }

  /**
   * 最近一次 complete compaction 是 threshold freshness barrier；失败、STOPPED、incomplete compaction 与
   * Resolver Rejected turn（{@code contextWindow == null}）不抹掉更早成功 usage。其它 Thread 拥有的共享 turn 是
   * ownership barrier。
   */
  private CompactionPreparation thresholdPreparation(
      ThreadState thread,
      EntryPath path,
      CompactionHistory.ClosedTurn latestTurn,
      CompactionConfig compaction,
      ExecutionSelection execution,
      CompactionPlanner planner) {
    CompactionHistory.ClosedTurn candidate = latestTurn;
    while (candidate != null) {
      TurnStartPayload start = (TurnStartPayload) candidate.start().payload();
      if (start.reason() == TurnStartReason.COMPACTION) {
        if (candidate.end().outcome() == TurnEndOutcome.COMPLETED) {
          return null;
        }
        candidate = CompactionHistory.previousClosedTurn(path, candidate);
        continue;
      }
      if (!thread.id().equals(start.ownerThreadId())) {
        return null;
      }
      if (start.contextWindow() == null) {
        candidate = CompactionHistory.previousClosedTurn(path, candidate);
        continue;
      }
      if (!start.settings().execution().equals(execution)) {
        return null;
      }
      Entry resultEntry = CompactionHistory.turnResultEntry(path, candidate.start());
      if (resultEntry != null
          && resultEntry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT
          && message.assistantMetadata() != null) {
        ModelUsage usage = message.assistantMetadata().usage();
        long contextTokens =
            usage.providerTotalTokens() != null && usage.providerTotalTokens() > 0
                ? usage.providerTotalTokens()
                : usage.categorizedTokens();
        contextTokens =
            Math.addExact(
                contextTokens, TokenEstimator.estimateVisibleTokensAfter(path, resultEntry.id()));
        if (start.maxOutputTokens() == null) {
          return null;
        }
        long threshold = compaction.softThreshold(start.contextWindow(), start.maxOutputTokens());
        if (contextTokens <= threshold) {
          return null;
        }
        return planner
            .prepare(path, CompactionTrigger.THRESHOLD, start.contextWindow())
            .orElse(null);
      }
      candidate = CompactionHistory.previousClosedTurn(path, candidate);
    }
    return null;
  }
}
