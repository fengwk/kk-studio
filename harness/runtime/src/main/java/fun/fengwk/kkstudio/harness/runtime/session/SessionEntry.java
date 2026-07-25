package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;

import java.time.Instant;
import java.util.Objects;

/**
 * Session Tree 上不可变的追加节点。
 *
 * <p>ROOT 没有 parent，其他 Entry 必须指向一个 parent。
 */
public record SessionEntry(
    long id,
    long sessionId,
    Long parentEntryId,
    EntryType type,
    EntryPayload payload,
    Instant createdAt) {

  public SessionEntry {
    if (id <= 0 || sessionId <= 0) {
      throw new IllegalArgumentException("entry and session ids must be positive");
    }
    if (parentEntryId != null && parentEntryId <= 0) {
      throw new IllegalArgumentException("parentEntryId must be positive when present");
    }
    type = Objects.requireNonNull(type, "type");
    payload = Objects.requireNonNull(payload, "payload");
    if (type != payload.type()) {
      throw new IllegalArgumentException("entry type does not match payload type");
    }
    if (type == EntryType.ROOT) {
      if (parentEntryId != null) {
        throw new IllegalArgumentException("ROOT entry must not carry parentEntryId");
      }
    } else if (parentEntryId == null) {
      throw new IllegalArgumentException("non-ROOT entry must carry parentEntryId");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
