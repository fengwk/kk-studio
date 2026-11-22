package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ContextPressureDetector;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

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
  public static CompactionTurns.CompactionTurn compactionTurnAtStart(
      EntryPath path, UUID startEntryId) {
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
  public static Entry turnResultEntry(EntryPath path, Entry turn) {
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
   * 从当前请求之前最近的 closed turn 回溯：最近一次完整压缩之后尚无成功完成的 INPUT / CONTINUATION， 即仍处于压缩后的重试窗口，与压缩 trigger
   * 无关。当前请求本身不参与判断。
   */
  public static boolean isPostCompactionRetry(EntryPath path, ClosedTurn precedingTurn) {
    for (ClosedTurn turn = precedingTurn; turn != null; turn = previousClosedTurn(path, turn)) {
      if (turn.end().outcome() != TurnEndOutcome.COMPLETED) {
        continue;
      }
      TurnStartReason reason = ((TurnStartPayload) turn.start().payload()).reason();
      if (reason == TurnStartReason.INPUT || reason == TurnStartReason.CONTINUATION) {
        return false;
      }
      if (reason == TurnStartReason.COMPACTION
          && compactionTurnAtStart(path, turn.start().id()).complete()) {
        return true;
      }
    }
    return false;
  }

  /** 判断是否达到上下文边界/溢出墙（ProviderErrorKind.CONTEXT_LIMIT 或 ContextPressureDetector 判定）。 */
  public static boolean isContextWall(TurnStartPayload start, Entry resultEntry) {
    if (resultEntry == null) {
      return false;
    }
    if (resultEntry.payload() instanceof AssistantErrorPayload error) {
      return ProviderErrorKind.CONTEXT_LIMIT.name().equals(error.error().code());
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
