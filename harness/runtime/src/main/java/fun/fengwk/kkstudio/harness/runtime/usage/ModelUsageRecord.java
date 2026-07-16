package fun.fengwk.kkstudio.harness.runtime.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化的模型调用账本快照；一次 Assistant Entry 只能写出一条记录。
 *
 * <p>字段一致性约束：
 *
 * <ul>
 *   <li>{@code id} / {@code sessionId} / {@code runId} / {@code assistantEntryId} 必须正整数
 *   <li>{@code attempt} 必须正整数，{@code turnIndex} 必须非负整数
 *   <li>{@code draft} 与 {@code createdAt} 不可空
 * </ul>
 */
public record ModelUsageRecord(
    long id,
    long sessionId,
    long runId,
    long assistantEntryId,
    int attempt,
    int turnIndex,
    ModelUsageDraft draft,
    Instant createdAt) {

  public ModelUsageRecord {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    if (runId <= 0) {
      throw new IllegalArgumentException("runId must be positive");
    }
    if (assistantEntryId <= 0) {
      throw new IllegalArgumentException("assistantEntryId must be positive");
    }
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    if (turnIndex < 0) {
      throw new IllegalArgumentException("turnIndex must not be negative");
    }
    draft = Objects.requireNonNull(draft, "draft");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
