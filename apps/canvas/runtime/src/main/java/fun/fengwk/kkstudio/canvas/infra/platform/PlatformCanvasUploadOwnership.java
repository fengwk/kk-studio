package fun.fengwk.kkstudio.canvas.infra.platform;

import fun.fengwk.kkstudio.canvas.CanvasUploadOwnership;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.util.Objects;
import java.util.UUID;

/** Storage-backed transfer from a user's READY upload to a Canvas Resource Blob reference. */
public class PlatformCanvasUploadOwnership implements CanvasUploadOwnership {

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;

  public PlatformCanvasUploadOwnership(
      StorageUploadService uploadService, StorageBlobManager blobManager) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  @Override
  public ReadyUpload lockReady(UUID uploadId, UUID userId) {
    StorageUploadService.ReadyUpload ready = uploadService.lockReady(uploadId, userId);
    return new ReadyUpload(ready.blobId(), ready.filename());
  }

  @Override
  public void retain(UUID blobId) {
    blobManager.retain(blobId);
  }

  @Override
  public void delete(UUID uploadId, UUID userId) {
    uploadService.delete(uploadId, userId);
  }
}
