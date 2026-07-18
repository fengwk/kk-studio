package fun.fengwk.kkstudio.harness.runtime.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化的模型调用账本快照；一次 Assistant Entry 只能写出一条记录。
 *
 * <p>关联键：{@code threadId + assistantEntryId}（不再使用 run/attempt/turn）。
 */
public record ModelUsageRecord(
    long id,
    long sessionId,
    long threadId,
    long assistantEntryId,
    ModelUsageDraft draft,
    Instant createdAt) {

  public ModelUsageRecord {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (assistantEntryId <= 0) {
      throw new IllegalArgumentException("assistantEntryId must be positive");
    }
    draft = Objects.requireNonNull(draft, "draft");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
