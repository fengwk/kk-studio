package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread。 */
public record Session(UUID id, Instant createdAt) {

  public Session {
    Objects.requireNonNull(id, "id");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
