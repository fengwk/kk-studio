package fun.fengwk.kkstudio.harness.environment.capability;

import java.util.Objects;
import java.util.UUID;

/** Platform-owned archive attachment for a single {@code skill.install} capability call. */
public record ResourceDownload(UUID transferId, UUID blobId) {

  public ResourceDownload {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(blobId, "blobId");
  }
}
