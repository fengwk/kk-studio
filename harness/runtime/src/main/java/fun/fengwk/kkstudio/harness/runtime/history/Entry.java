package fun.fengwk.kkstudio.harness.runtime.history;

import java.time.Instant;
import java.util.Objects;

/**
 * Session Entry Tree 上不可变的追加节点。
 *
 * <p>ROOT 没有 parent，其他 Entry 必须指向同一 Session 内的一个正数 parent Entry。ID 均为正数。
 */
public record Entry(
    long id, long sessionId, Long parentEntryId, EntryPayload payload, Instant createdAt) {

  public Entry {
    if (id <= 0) {
      throw new IllegalArgumentException("entry id must be positive");
    }
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    payload = Objects.requireNonNull(payload, "payload");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (payload.type().isRoot()) {
      if (parentEntryId != null) {
        throw new IllegalArgumentException("ROOT entry must not carry parentEntryId");
      }
    } else if (parentEntryId == null || parentEntryId <= 0) {
      throw new IllegalArgumentException("non-ROOT entry must carry a positive parentEntryId");
    }
  }
}
