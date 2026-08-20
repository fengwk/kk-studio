package fun.fengwk.kkstudio.harness.runtime.history;

/**
 * 自动压缩 turn 的 durable 摘要结果 payload（仅作为 COMPACTION turn 的 assistant result）。
 *
 * <p>只保存非空 {@code summaryText}：phase / trigger / cut / complete / execution model 全部从 enclosing
 * TURN_START（{@link TurnStartPayload#compaction()}）与紧邻完成的 TURN_END 派生——{@code complete = phase !=
 * HISTORY && TurnEnd COMPLETED}。不再持久化 tokensBefore / firstKeptEntryId / complete。
 */
public record CompactionPayload(String summaryText) implements EntryPayload {

  public CompactionPayload {
    if (summaryText == null || summaryText.isBlank()) {
      throw new IllegalArgumentException("summaryText must not be blank");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.COMPACTION;
  }
}
