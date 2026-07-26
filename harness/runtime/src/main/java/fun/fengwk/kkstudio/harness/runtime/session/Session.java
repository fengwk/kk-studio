package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;

/**
 * Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread。
 *
 * <p>{@code title} 为可空展示属性。{@code parentSessionId} 与 {@code parentInvocationId} 仅在 Child Session
 * 上有值，且必须同时存在。
 */
public record Session(
    long id,
    String title,
    Long parentSessionId,
    Long parentInvocationId,
    Instant createdAt,
    Instant updatedAt) {

  public Session {
    if (id <= 0) {
      throw new IllegalArgumentException("session id must be positive");
    }
    if (parentSessionId != null && parentSessionId <= 0) {
      throw new IllegalArgumentException("parentSessionId must be positive when present");
    }
    if (parentInvocationId != null && parentInvocationId <= 0) {
      throw new IllegalArgumentException("parentInvocationId must be positive when present");
    }
    if ((parentSessionId == null) != (parentInvocationId == null)) {
      throw new IllegalArgumentException(
          "parentSessionId and parentInvocationId must be present together");
    }
    if (title != null && title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank when present");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  public boolean isRoot() {
    return parentSessionId == null;
  }
}
