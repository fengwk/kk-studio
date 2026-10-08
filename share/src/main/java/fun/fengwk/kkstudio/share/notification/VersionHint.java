package fun.fengwk.kkstudio.share.notification;

import java.util.Objects;
import java.util.UUID;

/** Immutable entity/version hint; different versions remain distinct publications. */
public record VersionHint(UUID entityId, long version) {
  public VersionHint {
    Objects.requireNonNull(entityId, "entityId");
    if (version < 0) {
      throw new IllegalArgumentException("negative hint version");
    }
  }
}
