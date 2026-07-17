package fun.fengwk.kkstudio.studio.model;

import java.time.Instant;
import java.util.Objects;

/** Immutable content version of a Resource. */
public record ResourceVersion(
    long id,
    long resourceId,
    long version,
    PayloadRef payload,
    String metadataJson,
    String contentHash,
    Long producedByRunId,
    Instant createdAt) {

  public ResourceVersion {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(metadataJson, "metadataJson");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
