package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;

/**
 * 自动压缩 turn 的 durable 摘要结果 payload（仅作为 COMPACTION turn 的 assistant result）。
 *
 * <p>{@code summaryText} 必须非空：phase / trigger / cut / complete / execution model 全部从 enclosing
 * TURN_START（{@link TurnStartPayload#compaction()}）与紧邻完成的 TURN_END 派生——{@code complete = phase !=
 * HISTORY && TurnEnd COMPLETED}。不再持久化 tokensBefore / firstKeptEntryId / complete。
 *
 * <p>{@code assistantMetadata} 是这次压缩模型调用的原始 provider 元数据（stopReason / usage /
 * decodeDuration）：只有正式模型输出才携带，纯摘要组装显式传 null。null 表示没有真实模型用量事实，读取侧绝不据此伪造 0 用量；cost / pricing /
 * replay hash 不属于这里。
 */
public record CompactionPayload(String summaryText, AssistantMessageMetadata assistantMetadata)
    implements EntryPayload {

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
