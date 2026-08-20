package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 从 root-to-head EntryPath 派生每个已关闭 COMPACTION turn 的完整事实；{@link CompactionPayload} 只保存 summaryText，
 * 因此 phase / trigger / executionModel / cut 从 enclosing TURN_START 的 {@link CompactionStart}
 * 读取，complete = {@code phase != HISTORY 且紧邻 TURN_END COMPLETED}（{@link CompactionTurn#complete()}）。
 *
 * <p>切分 / fallback reducer、previous-summary 投影、no-gain 估算与 manual control 共用本扫描，禁止各自重复配对逻辑。
 */
public final class CompactionTurns {

  private CompactionTurns() {}

  /**
   * 一次已完全关闭（start+end 均已看到）或仍在 open 的 COMPACTION turn 的不可变事实。
   *
   * <p>{@code end} 为 null 表示该压缩 turn 尚未关闭；{@code result} 为 null 表示 turn 以错误 / 停止 barrier 结束 （无
   * COMPACTION payload）。
   */
  public record CompactionTurn(
      TurnStartPayload start,
      CompactionPayload result,
      TurnEndPayload end,
      int startIndex,
      int resultIndex,
      int endIndex) {

    public CompactionTurn {
      start = Objects.requireNonNull(start, "start");
      if (start.reason() != TurnStartReason.COMPACTION) {
        throw new IllegalArgumentException("compaction turn facts require a COMPACTION TURN_START");
      }
      if (result != null && end == null) {
        throw new IllegalArgumentException(
            "a completed compaction result requires its enclosing TURN_END");
      }
    }

    /** complete = phase != HISTORY 且 TURN_END COMPLETED（点 3）。 */
    public boolean complete() {
      return start.compaction().phase() != CompactionPhase.HISTORY
          && result != null
          && end != null
          && end.outcome() == TurnEndOutcome.COMPLETED;
    }

    /** 该压缩 turn 是否以 COMPLETED 关闭（HISTORY 部分成功也在此列）。 */
    public boolean completed() {
      return end != null && end.outcome() == TurnEndOutcome.COMPLETED;
    }

    public CompactionPhase phase() {
      return start.compaction().phase();
    }

    public CompactionTrigger trigger() {
      return start.compaction().trigger();
    }

    public CompactionStart freezing() {
      return start.compaction();
    }
  }

  /** 扫描 path，返回按路径顺序排列的全部 COMPACTION turn 事实（open 且无 result/end 的也返回）。 */
  public static List<CompactionTurn> scan(EntryPath path) {
    Objects.requireNonNull(path, "path");
    List<Entry> entries = path.entries();
    List<CompactionTurn> turns = new ArrayList<>();
    TurnStartPayload open = null;
    int openIndex = -1;
    CompactionPayload openResult = null;
    int openResultIndex = -1;
    for (int i = 0; i < entries.size(); i++) {
      EntryPayload payload = entries.get(i).payload();
      if (payload instanceof TurnStartPayload start) {
        open = start.reason() == TurnStartReason.COMPACTION ? start : null;
        openIndex = start.reason() == TurnStartReason.COMPACTION ? i : -1;
        openResult = null;
        openResultIndex = -1;
        continue;
      }
      if (payload instanceof TurnEndPayload end) {
        if (open != null) {
          turns.add(new CompactionTurn(open, openResult, end, openIndex, openResultIndex, i));
        }
        open = null;
        openIndex = -1;
        openResult = null;
        openResultIndex = -1;
        continue;
      }
      if (payload instanceof CompactionPayload result) {
        if (open == null) {
          throw new IllegalStateException(
              "COMPACTION payload at index " + i + " has no enclosing COMPACTION TURN_START");
        }
        openResult = result;
        openResultIndex = i;
      }
    }
    if (open != null) {
      turns.add(new CompactionTurn(open, openResult, null, openIndex, openResultIndex, -1));
    }
    return List.copyOf(turns);
  }

  /** 路径上最近一次 complete 压缩（没有则 empty）。 */
  public static Optional<CompactionTurn> latestComplete(EntryPath path) {
    List<CompactionTurn> turns = scan(path);
    for (int i = turns.size() - 1; i >= 0; i--) {
      if (turns.get(i).complete()) {
        return Optional.of(turns.get(i));
      }
    }
    return Optional.empty();
  }

  /** 按 result Entry id 查找已关闭的 COMPACTION turn（找不到抛 {@link IllegalStateException} fail closed）。 */
  public static CompactionTurn ofResult(EntryPath path, UUID resultEntryId) {
    Objects.requireNonNull(resultEntryId, "resultEntryId");
    for (CompactionTurn turn : scan(path)) {
      if (turn.result != null && turn.resultIndex >= 0) {
        Entry entry = path.entries().get(turn.resultIndex);
        if (entry.id().equals(resultEntryId)) {
          return turn;
        }
      }
    }
    throw new IllegalStateException(
        "compaction result entry " + resultEntryId + " is not on the current path");
  }

  /** 返回 path 中指定索引处的 Entry id（供调用方获取 result Entry 本身）。 */
  public static Entry entryAt(EntryPath path, int index) {
    return path.entries().get(index);
  }
}
