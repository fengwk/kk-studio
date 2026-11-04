package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/** 冻结 manifest 与输出校验所需的最小不可变 Blob facts。 */
public record BlobFacts(
    UUID blobId, String mediaType, long sizeBytes, Long width, Long height, Long durationMs) {

  public BlobFacts {
    Objects.requireNonNull(blobId, "blobId");
  }
}
