package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ContextPressureDetector;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 自动压缩规划与手动压缩共用的 closed-turn 与 history 事实投影。
 *
 * <p>统一提供已关闭 Turn 配对扫描、前序 Turn 回溯、CompactionTurn 查询、Turn 结果 Entry 提取、 上下文溢出屏障与 pending continuation
 * obligation 判定，避免两套 closed-turn 逻辑漂移。
 */
public final class CompactionHistory {

  /** 已关闭的 Turn 视图：包含 turnStart Entry 与对应的 TurnEndPayload。 */
  public record ClosedTurn(Entry start, TurnEndPayload end) {
    public ClosedTurn {
      Objects.requireNonNull(start, "start");
      Objects.requireNonNull(end, "end");
    }
  }

  private CompactionHistory() {}

  /** 路径末尾最近的已关闭 turn；若末尾处于 open turn 或无任何 turn 则返回 null。 */
  public static ClosedTurn latestClosedTurn(EntryPath path) {
    Objects.requireNonNull(path, "path");
    return latestClosedTurn(path, path.entries().size());
  }

  /** {@code beforeExclusive} 之前最近的已关闭 turn；该范围末尾仍是 open turn / 无任何 turn 时返回 null。 */
  static ClosedTurn latestClosedTurn(EntryPath path, int beforeExclusive) {
    Objects.requireNonNull(path, "path");
    for (int i = beforeExclusive - 1; i >= 0; i--) {
      EntryPayload payload = path.entries().get(i).payload();
      if (payload instanceof TurnEndPayload end) {
        for (int j = i - 1; j >= 0; j--) {
          Entry entry = path.entries().get(j);
          if (entry.id().equals(end.turnStartEntryId())) {
            return new ClosedTurn(entry, end);
          }
        }
        return null;
      }
      if (payload instanceof TurnStartPayload) {
        // 该范围末尾仍处于 open turn；防御性返回 null
        return null;
      }
    }
    return null;
  }

  /** 当前 turn 之前最近的已关闭 turn；无前序 closed turn 则返回 null。 */
  public static ClosedTurn previousClosedTurn(EntryPath path, ClosedTurn turn) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(turn, "turn");
    int startIndex = indexOfEntry(path.entries(), turn.start().id());
    return startIndex < 0 ? null : latestClosedTurn(path, startIndex);
  }

  /** 根据 startEntryId 查找对应的 CompactionTurn 事实。 */
  static CompactionTurns.CompactionTurn compactionTurnAtStart(EntryPath path, UUID startEntryId) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(startEntryId, "startEntryId");
    for (CompactionTurns.CompactionTurn turn : CompactionTurns.scan(path)) {
      if (path.entries().get(turn.startIndex()).id().equals(startEntryId)) {
        return turn;
      }
    }
    throw new IllegalStateException("closed COMPACTION turn is missing from derived turn facts");
  }

  /**
   * 路径上指定 turn 的 assistant 结果 Entry（ASSISTANT MESSAGE / ASSISTANT_ERROR / ASSISTANT_ABORTED）；无则返回
   * null。
   */
  static Entry turnResultEntry(EntryPath path, Entry turn) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(turn, "turn");
    boolean inTurn = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(turn.id())) {
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

  /**
   * 当前 open Compaction 之前是否仍有本 Thread 拥有的普通 continuation obligation。连续 COMPACTION turns
   * 只承载压缩阶段/fallback；遇到 foreign owner 或首个普通 closed turn 即停止。
   */
  public static boolean hasPendingOwnedContinuation(
      ThreadState thread, EntryPath path, UUID currentCompactionStartEntryId) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(currentCompactionStartEntryId, "currentCompactionStartEntryId");
    int currentStartIndex = indexOfEntry(path.entries(), currentCompactionStartEntryId);
    if (currentStartIndex < 0) {
      throw new IllegalStateException(
          "current compaction start is not on the path: " + currentCompactionStartEntryId);
    }
    ClosedTurn candidate = latestClosedTurn(path, currentStartIndex);
    while (candidate != null) {
      TurnStartPayload start = (TurnStartPayload) candidate.start().payload();
      if (!thread.id().equals(start.ownerThreadId())) {
        return false;
      }
      if (start.reason() != TurnStartReason.COMPACTION) {
        return candidate.end().outcome() == TurnEndOutcome.COMPLETED
            && candidate.end().continueModel();
      }
      candidate = previousClosedTurn(path, candidate);
    }
    return false;
  }

  /** 当前失败 turn 是否正是 complete OVERFLOW compaction 创建的 immediate CONTINUATION 重试。 */
  static boolean isOverflowRecoveryRetry(EntryPath path, ClosedTurn latestTurn) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(latestTurn, "latestTurn");
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
    CompactionTurns.CompactionTurn previousCompaction =
        compactionTurnAtStart(path, previousTurn.start().id());
    return previousCompaction.complete()
        && previousCompaction.trigger() == CompactionTrigger.OVERFLOW;
  }

  /** 判断是否达到上下文边界/溢出墙（ProviderErrorKind.OVERFLOW 或 ContextPressureDetector 判定）。 */
  static boolean isContextWall(TurnStartPayload start, Entry resultEntry) {
    if (resultEntry == null) {
      return false;
    }
    if (resultEntry.payload() instanceof AssistantErrorPayload error) {
      return ProviderErrorKind.OVERFLOW.name().equals(error.error().code());
    }
    if (resultEntry.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT
        && message.assistantMetadata() != null
        && start.contextWindow() != null) {
      return ContextPressureDetector.detectResponse(
          message.assistantMetadata().stopReason(),
          message.assistantMetadata().usage(),
          start.contextWindow());
    }
    return false;
  }

  /** 查找 entry 在列表中的下标。 */
  private static int indexOfEntry(List<Entry> entries, UUID entryId) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(entryId, "entryId");
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }
}
