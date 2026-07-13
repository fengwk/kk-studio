package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;

/** 不可变、仅追加的 Session Tree 节点。 */
public record SessionEntry(
    String entryId,
    String sessionId,
    String parentEntryId,
    SessionEntryType type,
    SessionEntryPayload payload,
    Instant createdAt) {
  public SessionEntry {
    entryId = requireNonBlank(entryId, "entryId");
    sessionId = requireNonBlank(sessionId, "sessionId");
    type = Objects.requireNonNull(type, "type");
    payload = Objects.requireNonNull(payload, "payload");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (type != payload.type()) {
      throw new IllegalArgumentException("entry type does not match payload type");
    }
    if (parentEntryId != null && parentEntryId.isBlank()) {
      throw new IllegalArgumentException("parentEntryId must not be blank when present");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
