package fun.fengwk.kkstudio.canvas.infra.platform;

import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.util.Objects;
import java.util.UUID;

/** Resolves attached Canvas resources and signs access to their blobs. */
public class CanvasResourceUrlService {

  private final CanvasQueryService queryService;
  private final StorageBlobManager blobManager;

  public CanvasResourceUrlService(CanvasQueryService queryService, StorageBlobManager blobManager) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  public StoragePresignedUrlDTO presign(UUID canvasId, UUID resourceId, boolean original) {
    CanvasResource resource =
        queryService
            .findResource(canvasId, resourceId)
            .orElseThrow(
                () ->
                    queryService.findDocument(canvasId).isEmpty()
                        ? new ResourceNotFoundException("unknown canvas: " + canvasId)
                        : new ResourceNotFoundException("unknown resource: " + resourceId));
    if (resource.isText()) {
      throw new IllegalArgumentException("TEXT resource has no blob content");
    }
    return original
        ? blobManager.presignOriginalUrl(resource.blobId())
        : blobManager.presignPreviewUrl(resource.blobId());
  }

  public static class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
      super(message);
    }
  }
}
