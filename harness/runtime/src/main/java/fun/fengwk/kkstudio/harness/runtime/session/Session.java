package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;

/** Workspace 作用域的 Session 聚合快照；leaf 是唯一活动分支的末端。 */
public record Session(
    String sessionId,
    long workspaceId,
    String parentSessionId,
    String leafEntryId,
    Instant createdAt) {
  public Session {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (workspaceId <= 0) {
      throw new IllegalArgumentException("workspaceId must be positive");
    }
    if (parentSessionId != null && parentSessionId.isBlank()) {
      throw new IllegalArgumentException("parentSessionId must not be blank when present");
    }
    if (leafEntryId != null && leafEntryId.isBlank()) {
      throw new IllegalArgumentException("leafEntryId must not be blank when present");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
