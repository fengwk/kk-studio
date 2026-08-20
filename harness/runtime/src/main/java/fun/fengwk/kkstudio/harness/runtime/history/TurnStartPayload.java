package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Model response turn 开始处的不可变 durable 边界。
 *
 * <p>{@code ownerThreadId} 始终记录创建该 turn 的 Thread。{@code contextWindow} 与 {@code maxOutputTokens} 仅在
 * Resolver 成功时一起为正整数冻结，二者要么同为 null（rejected / 候补）要么同为非 null； {@code compaction} 非空当且仅当 {@code
 * reason == COMPACTION}，冻结本次压缩的最小元数据。
 */
public record TurnStartPayload(
    TurnStartReason reason,
    BranchSettings settings,
    UUID ownerThreadId,
    Integer contextWindow,
    Integer maxOutputTokens,
    CompactionStart compaction)
    implements EntryPayload {

  /** 候补 path / rejected turn：contextWindow 与 maxOutputTokens 尚未补齐，无压缩元数据。 */
  public TurnStartPayload(TurnStartReason reason, BranchSettings settings, UUID ownerThreadId) {
    this(reason, settings, ownerThreadId, null, null, null);
  }

  public TurnStartPayload {
    reason = Objects.requireNonNull(reason, "reason");
    settings = Objects.requireNonNull(settings, "settings");
    ownerThreadId = Objects.requireNonNull(ownerThreadId, "ownerThreadId");
    if ((contextWindow == null) != (maxOutputTokens == null)) {
      throw new IllegalArgumentException(
          "contextWindow and maxOutputTokens must be resolved or rejected together");
    }
    if (contextWindow != null && contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive when present");
    }
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive when present");
    }
    if ((reason == TurnStartReason.COMPACTION) != (compaction != null)) {
      throw new IllegalArgumentException(
          "compaction metadata is required iff reason is COMPACTION");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_START;
  }
}
