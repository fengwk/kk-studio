package fun.fengwk.kkstudio.platform.canvas.function;

import org.springframework.beans.factory.ObjectProvider;
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

  private final ObjectProvider<S3StorageService> storageServices;
  private final ObjectProvider<StorageBlobManager> blobManagers;

  public PlatformCanvasFunctionBlobAccess(
      ObjectProvider<S3StorageService> storageServices,
      ObjectProvider<StorageBlobManager> blobManagers) {
    this.storageServices = Objects.requireNonNull(storageServices, "storageServices");
    this.blobManagers = Objects.requireNonNull(blobManagers, "blobManagers");
  }

  @Override
  public Optional<BlobFacts> findFacts(UUID blobId) {
    StorageBlob blob = requireGlobalBlobManager().getBlob(Objects.requireNonNull(blobId, "blobId"));
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
    S3ObjectStream object =
        requireStorageService().readObject(StorageObjectKeys.blobOriginal(blobId));
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
    return requireRuntimeBlobManager()
        .presignOriginalUrl(Objects.requireNonNull(blobId, "blobId"))
        .getUrl();
  }

  private S3StorageService requireStorageService() {
    return Objects.requireNonNull(
        storageServices.getIfAvailable(), "S3 storage is required for Canvas Function runtime");
  }

  private StorageBlobManager requireRuntimeBlobManager() {
    return Objects.requireNonNull(
        blobManagers.getIfAvailable(),
        "StorageBlobManager is required for Canvas Function runtime");
  }

  private StorageBlobManager requireGlobalBlobManager() {
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      throw new IllegalStateException("global blob storage is unavailable");
    }
    return blobManager;
  }
}
