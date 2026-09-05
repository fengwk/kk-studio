package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;

/**
 * 自动压缩规划器：按 owned HISTORY -&gt; fallback -&gt; hard overflow -&gt; eligible threshold 的顺序计算下一次自动压缩。
 *
 * <p>每次决策使用同一个 {@link CompactionConfig} 快照构建或驱动 {@link CompactionPlanner}， 保证阈值判定、fallbackModel
 * 与规划逻辑完全一致。
 */
public final class AutomaticCompactionPlanner {

  public AutomaticCompactionPlanner() {}

  /**
   * 按 owned HISTORY -&gt; fallback -&gt; hard overflow -&gt; eligible threshold 的顺序计算下一次压缩。
   * 无需自动压缩时返回 null。
   */
  public CompactionPreparation plan(
      ThreadState thread, EntryPath path, CompactionConfig compaction, boolean thresholdEligible) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(compaction, "compaction");

    CompactionHistory.ClosedTurn latestTurn = CompactionHistory.latestClosedTurn(path);
    if (latestTurn == null) {
      return null;
    }
    Entry latestStart = latestTurn.start();
    TurnStartPayload latestStartPayload = (TurnStartPayload) latestStart.payload();
    CompactionPlanner planner = new CompactionPlanner(compaction);

    if (latestStartPayload.reason() == TurnStartReason.COMPACTION) {
      if (!thread.id().equals(latestStartPayload.ownerThreadId())) {
        return null;
      }
      CompactionPreparation historyContinuation =
          ownedHistoryContinuation(thread, path, latestTurn, planner);
      if (historyContinuation != null) {
        return historyContinuation;
      }
      CompactionTurns.CompactionTurn compactionTurn =
          CompactionHistory.compactionTurnAtStart(path, latestStart.id());
      if (latestTurn.end().outcome() == TurnEndOutcome.FAILED
          && compaction.fallbackModel() != null
          && latestStartPayload
              .compaction()
              .executionModel()
              .equals(latestStartPayload.settings().model())
          && !compaction.fallbackModel().equals(latestStartPayload.compaction().executionModel())) {
        return planner.prepareFallback(path, compactionTurn);
      }
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
    return thresholdPreparation(thread, path, latestTurn, compaction, planner);
  }

  /** owned completed HISTORY turn 的机械 TURN_PREFIX obligation；其它 head 返回 null。 */
  private CompactionPreparation ownedHistoryContinuation(
      ThreadState thread,
      EntryPath path,
      CompactionHistory.ClosedTurn latestTurn,
      CompactionPlanner planner) {
    if (latestTurn.end().outcome() != TurnEndOutcome.COMPLETED
        || !(latestTurn.start().payload() instanceof TurnStartPayload start)
        || start.reason() != TurnStartReason.COMPACTION
        || !thread.id().equals(start.ownerThreadId())) {
      return null;
    }
    CompactionTurns.CompactionTurn compactionTurn =
        CompactionHistory.compactionTurnAtStart(path, latestTurn.start().id());
    if (compactionTurn.phase() != CompactionPhase.HISTORY || compactionTurn.result() == null) {
      return null;
    }
    return planner.prepareTurnPrefix(path, compactionTurn);
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
      CompactionPlanner planner) {
    CompactionHistory.ClosedTurn candidate = latestTurn;
    while (candidate != null) {
      TurnStartPayload start = (TurnStartPayload) candidate.start().payload();
      if (start.reason() == TurnStartReason.COMPACTION) {
        CompactionTurns.CompactionTurn turn =
            CompactionHistory.compactionTurnAtStart(path, candidate.start().id());
        if (turn.complete()) {
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
      if (!start.settings().model().equals(path.baseSettings().model())) {
        return null;
      }
      Entry resultEntry = CompactionHistory.turnResultEntry(path, candidate.start());
      if (resultEntry != null
          && resultEntry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT
          && message.assistantMetadata() != null) {
        ModelUsage usage = message.assistantMetadata().usage();
        long contextTokens =
            usage.providerTotalTokens() > 0
                ? usage.providerTotalTokens()
                : usage.categorizedTokens();
        contextTokens =
            Math.addExact(
                contextTokens,
                CompactionPlanner.estimateVisibleTokensAfter(path, resultEntry.id()));
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
