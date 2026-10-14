package fun.fengwk.kkstudio.platform.canvas.function;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.platform.storage.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Canvas Function Runtime 到 Platform 全局 Blob/S3 能力的适配器。 */
@Component
public final class PlatformCanvasFunctionBlobAccess implements CanvasFunctionBlobAccess {

  private final S3StorageService storageService;
  private final StorageBlobManager blobManager;

  public PlatformCanvasFunctionBlobAccess(
      S3StorageService storageService, StorageBlobManager blobManager) {
    this.storageService = Objects.requireNonNull(storageService, "storageService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  @Override
  public Optional<BlobFacts> findFacts(UUID blobId) {
    StorageBlob blob = blobManager.getBlob(Objects.requireNonNull(blobId, "blobId"));
    if (blob == null) {
      return Optional.empty();
    }
    return Optional.of(
        new BlobFacts(
            blob.getId(),
            blob.getMediaType(),
            blob.getSizeBytes(),
            blob.getWidth(),
            blob.getHeight(),
            blob.getDurationMs()));
  }

  @Override
  public CanvasFunctionResourceStream openOriginal(UUID blobId, long expectedSizeBytes) {
    S3ObjectStream object = storageService.readObject(StorageObjectKeys.blobOriginal(blobId));
    if (object.metadata().contentLength() != expectedSizeBytes) {
      try {
        object.close();
      } catch (IOException closeError) {
        throw new IllegalStateException("failed to close mismatched S3 object stream", closeError);
      }
      throw new IllegalArgumentException("S3 original length does not match frozen blob size");
    }
    return new CanvasFunctionResourceStream(
        object.inputStream(), object.metadata().contentLength(), object);
  }

  @Override
  public String originalUrl(UUID blobId, long expiresSeconds) {
    return blobManager.presignOriginalUrl(Objects.requireNonNull(blobId, "blobId")).getUrl();
  }
}
