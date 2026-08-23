package fun.fengwk.kkstudio.canvas.function;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Function Runtime 读取冻结 Blob facts 与 original 内容的窄端口。 */
public interface CanvasFunctionBlobAccess {

  Optional<BlobFacts> findFacts(UUID blobId);

  CanvasFunctionResourceStream openOriginal(UUID blobId, long expectedSizeBytes);

  String originalUrl(UUID blobId, long expiresSeconds);

  /** 冻结 manifest 与输出校验所需的最小不可变 Blob facts。 */
  record BlobFacts(
      UUID blobId, String mediaType, long sizeBytes, Long width, Long height, Long durationMs) {

    public BlobFacts {
      Objects.requireNonNull(blobId, "blobId");
    }
  }
}
