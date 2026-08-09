package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;

import java.util.Objects;

/**
 * 自动压缩 turn 的 durable 摘要结果 payload（仅作为 COMPACTION turn 的 assistant result）。
 *
 * <p>{@code complete=false} 只属于 HISTORY 阶段（切分 turn 的第一次调用）；FULL / TURN_PREFIX 成功写出 {@code
 * complete=true} 的最终 summary。{@code firstKeptEntryId} 是保留区起点（可包含 cut 前相邻的非上下文元数据）， {@code
 * cutEntryId} 是实际第一个保留的上下文消息；切分 turn 额外携带 {@code turnPrefixStartEntryId}（FULL 必须为 null）。 {@code
 * summaryText} 始终非空（ModelResponseValidator 保证压缩成功响应的文本非空）。
 */
public record CompactionPayload(
    CompactionPhase phase,
    CompactionTrigger trigger,
    long tokensBefore,
    boolean complete,
    String summaryText,
    long firstKeptEntryId,
    long cutEntryId,
    Long turnPrefixStartEntryId)
    implements EntryPayload {

  public CompactionPayload {
    phase = Objects.requireNonNull(phase, "phase");
    trigger = Objects.requireNonNull(trigger, "trigger");
    if (tokensBefore < 0) {
      throw new IllegalArgumentException("tokensBefore must not be negative");
    }
    if (summaryText == null || summaryText.isBlank()) {
      throw new IllegalArgumentException("summaryText must not be blank");
    }
    if (firstKeptEntryId <= 0) {
      throw new IllegalArgumentException("firstKeptEntryId must be positive");
    }
    if (cutEntryId <= 0) {
      throw new IllegalArgumentException("cutEntryId must be positive");
    }
    if (turnPrefixStartEntryId != null && turnPrefixStartEntryId <= 0) {
      throw new IllegalArgumentException("turnPrefixStartEntryId must be positive");
    }
    if (phase == CompactionPhase.FULL) {
      if (turnPrefixStartEntryId != null) {
        throw new IllegalArgumentException("FULL compaction must not carry turnPrefixStartEntryId");
      }
    } else if (turnPrefixStartEntryId == null) {
      throw new IllegalArgumentException("split compaction phases require turnPrefixStartEntryId");
    }
    if ((phase == CompactionPhase.HISTORY) != !complete) {
      throw new IllegalArgumentException(
          "HISTORY compaction must be incomplete and other phases complete");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.COMPACTION;
  }
}
