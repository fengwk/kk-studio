package fun.fengwk.kkstudio.core.harness.observability.service;

import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict, validation-aware lookup of persisted artifacts. Returns {@link Result#FOUR_HUNDRED} for
 * blank or non-decimal identifiers and {@link Result#NOT_FOUND} for unknown or non-positive ids.
 */
public final class HarnessArtifactResolution {

  private final ArtifactStore artifactStore;

  public HarnessArtifactResolution(ArtifactStore artifactStore) {
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
  }

  public Result resolve(String artifactId) {
    if (artifactId == null || artifactId.isBlank()) {
      return Result.FOUR_HUNDRED;
    }
    String trimmed = artifactId.trim();
    long parsed;
    try {
      parsed = Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      return Result.FOUR_HUNDRED;
    }
    if (parsed <= 0) {
      return Result.FOUR_HUNDRED;
    }
    Optional<Artifact> found = artifactStore.find(trimmed);
    if (found.isEmpty()) {
      return Result.NOT_FOUND;
    }
    return new Result(found.get());
  }

  /** Three-valued outcome so the controller can translate into 200 / 400 / 404 deterministically. */
  public static final class Result {
    public static final Result NOT_FOUND = new Result(null);
    public static final Result FOUR_HUNDRED = new Result(null);

    private final Artifact artifact;

    private Result(Artifact artifact) {
      this.artifact = artifact;
    }

    public boolean found() {
      return artifact != null;
    }

    public Artifact artifact() {
      return artifact;
    }
  }
}