package fun.fengwk.kkstudio.platform.orchestration;

import java.util.Objects;

/** Opaque product ownership identity. */
public record SessionOwner(String kind, String id) {
  public SessionOwner {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
    if (kind.isBlank() || id.isBlank()) {
      throw new IllegalArgumentException("Owner kind and id must not be blank");
    }
  }
}
