package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;

/** Session 聚合快照：共享 append-only Entry Tree 与稳定 Main Thread。 */
public record Session(
    long id,
    long mainThreadId,
    String title,
    Long parentSessionId,
    long rootSessionId,
    Long parentInvocationId,
    int depth,
    long version,
    Instant createdAt,
    Instant updatedAt) {
  public Session {
    if (id <= 0 || rootSessionId <= 0 || mainThreadId <= 0) {
      throw new IllegalArgumentException("session, root and mainThread ids must be positive");
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
        || (parentSessionId != null && (rootSessionId == id || depth == 0))) {
      throw new IllegalArgumentException("root and child session fields are inconsistent");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  public static Session root(long id, long mainThreadId, String title, Instant now) {
    return new Session(id, mainThreadId, title, null, id, null, 0, 0, now, now);
  }

  public static Session child(
      long id,
      long mainThreadId,
      String title,
      long parentSessionId,
      long rootSessionId,
      long parentInvocationId,
      int depth,
      Instant now) {
    return new Session(
        id,
        mainThreadId,
        title,
        parentSessionId,
        rootSessionId,
        parentInvocationId,
        depth,
        0,
        now,
        now);
  }
}
