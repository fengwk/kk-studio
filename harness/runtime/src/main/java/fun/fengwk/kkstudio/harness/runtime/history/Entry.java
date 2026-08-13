package fun.fengwk.kkstudio.harness.runtime.history;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Session Entry Tree 上不可变的追加节点。
 *
 * <p>ROOT 没有 parent，其他 Entry 必须指向同一 Session 内的一个 parent Entry。
 */
public record Entry(
    UUID id, UUID sessionId, UUID parentEntryId, EntryPayload payload, Instant createdAt) {

  public Entry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sessionId, "sessionId");
    payload = Objects.requireNonNull(payload, "payload");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (payload.type().isRoot()) {
      if (parentEntryId != null) {
        throw new IllegalArgumentException("ROOT entry must not carry parentEntryId");
      }
    } else if (parentEntryId == null) {
      throw new IllegalArgumentException("non-ROOT entry must carry a parentEntryId");
    }
    if (payload instanceof ModelAttemptFailurePayload failure
        && failure.retryAt().isBefore(createdAt)) {
      throw new IllegalArgumentException(
          "model attempt failure retryAt must not precede entry createdAt");
    }
  }
}
