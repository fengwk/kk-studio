package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;

/** Global Session 聚合快照；id 是唯一业务与持久化标识，父子关系只由 session/root/parent 决定。 */
public record Session(
    long id,
    Long agentDefinitionId,
    String title,
    Long leafEntryId,
    Long activeRunId,
    Long parentSessionId,
    long rootSessionId,
    Long parentInvocationId,
    int depth,
    boolean yoloEnabled,
    long version,
    Instant createdAt,
    Instant updatedAt) {
  public Session {
    if (id <= 0 || rootSessionId <= 0) {
      throw new IllegalArgumentException("session and root ids must be positive");
    }
    if (agentDefinitionId != null && agentDefinitionId <= 0) {
      throw new IllegalArgumentException("agentDefinitionId must be positive when present");
    }
    if (leafEntryId != null && leafEntryId <= 0) {
      throw new IllegalArgumentException("leafEntryId must be positive when present");
    }
    if (activeRunId != null && activeRunId <= 0) {
      throw new IllegalArgumentException("activeRunId must be positive when present");
    }
    if (parentSessionId != null && parentSessionId <= 0) {
      throw new IllegalArgumentException("parentSessionId must be positive when present");
    }
    if (parentInvocationId != null && parentInvocationId <= 0) {
      throw new IllegalArgumentException("parentInvocationId must be positive when present");
    }
    if (depth < 0 || version < 0) {
      throw new IllegalArgumentException("depth and version must not be negative");
    }
    if ((parentSessionId == null
            && (rootSessionId != id || depth != 0 || parentInvocationId != null))
        || (parentSessionId != null && (rootSessionId == id || depth == 0 || yoloEnabled))) {
      throw new IllegalArgumentException("root and child session fields are inconsistent");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  public static Session root(
      long id, Long agentDefinitionId, String title, boolean yoloEnabled, Instant now) {
    return new Session(
        id, agentDefinitionId, title, null, null, null, id, null, 0, yoloEnabled, 0, now, now);
  }
}
