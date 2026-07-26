package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;

/** Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread。 */
public record Session(long id, String title, Instant createdAt) {

  public Session {
    if (id <= 0) {
      throw new IllegalArgumentException("session id must be positive");
    }
    if (title != null && title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank when present");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
