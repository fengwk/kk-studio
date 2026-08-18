package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Model response turn 开始处的不可变 durable 边界。
 *
 * <p>{@code ownerThreadId} 始终记录创建该 turn 的 Thread。{@code contextWindow} 仅在 Resolver 成功时为冻结正整数；
 * Resolver rejected 时为 null。候选 path 可先用 {@code contextWindow=null}，第二阶段 commit 再按 Resolver 结果补齐。
 */
public record TurnStartPayload(
    TurnStartReason reason, BranchSettings settings, UUID ownerThreadId, Integer contextWindow)
    implements EntryPayload {

  /** 候选 path / rejected turn：contextWindow 尚未补齐。 */
  public TurnStartPayload(TurnStartReason reason, BranchSettings settings, UUID ownerThreadId) {
    this(reason, settings, ownerThreadId, null);
  }

  public TurnStartPayload {
    reason = Objects.requireNonNull(reason, "reason");
    settings = Objects.requireNonNull(settings, "settings");
    ownerThreadId = Objects.requireNonNull(ownerThreadId, "ownerThreadId");
    if (contextWindow != null && contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive when present");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_START;
  }
}
