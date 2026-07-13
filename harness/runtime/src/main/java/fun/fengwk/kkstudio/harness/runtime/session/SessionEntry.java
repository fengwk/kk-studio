package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;

/** 不可变、仅追加的 Session Tree 节点；不包含 version 或更新时间。 */
public record SessionEntry(
    long id,
    long sessionId,
    Long parentEntryId,
    Long runId,
    SessionEntryType type,
    SessionEntryPayload payload,
    Instant createdAt) {
  public SessionEntry {
    if (id <= 0 || sessionId <= 0) {
      throw new IllegalArgumentException("entry and session ids must be positive");
    }
    if (parentEntryId != null && parentEntryId <= 0) {
      throw new IllegalArgumentException("parentEntryId must be positive when present");
    }
    if (runId != null && runId <= 0) {
      throw new IllegalArgumentException("runId must be positive when present");
    }
    type = Objects.requireNonNull(type, "type");
    payload = Objects.requireNonNull(payload, "payload");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (type != payload.type()) {
      throw new IllegalArgumentException("entry type does not match payload type");
    }
  }
}
