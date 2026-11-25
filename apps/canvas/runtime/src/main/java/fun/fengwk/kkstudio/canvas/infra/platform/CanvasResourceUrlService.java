package fun.fengwk.kkstudio.canvas.infra.platform;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.util.Objects;
import java.util.UUID;

/** Resolves attached Canvas resources and signs access to their blobs. */
public class CanvasResourceUrlService {

  private final CanvasQueryService queryService;
  private final StorageBlobManager blobManager;
  private final ResourceAccess resourceAccess;

  public CanvasResourceUrlService(
      CanvasQueryService queryService,
      StorageBlobManager blobManager,
      ResourceAccess resourceAccess) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.resourceAccess = Objects.requireNonNull(resourceAccess, "resourceAccess");
  }

  public StoragePresignedUrlDTO presign(
      StudioPrincipal principal, UUID canvasId, UUID blobId, boolean original) {
    resourceAccess.require(principal, Kind.CANVAS, canvasId);
    if (!queryService.containsHeldBlob(canvasId, blobId)) {
      throw ResourceAccess.notFound();
    }
    return original
        ? blobManager.presignOriginalUrl(blobId)
        : blobManager.presignPreviewUrl(blobId);
  }
}
