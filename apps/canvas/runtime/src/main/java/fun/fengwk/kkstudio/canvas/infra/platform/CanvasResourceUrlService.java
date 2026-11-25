package fun.fengwk.kkstudio.canvas.infra.platform;

import org.springframework.http.ContentDisposition;

import fun.fengwk.kkstudio.canvas.CanvasValidation;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.nio.charset.StandardCharsets;
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

  public StoragePresignedUrlDTO originalUrl(
      StudioPrincipal principal, UUID canvasId, UUID blobId, String downloadName) {
    requireHeldBlob(principal, canvasId, blobId);
    if (downloadName == null) {
      return blobManager.presignOriginalUrl(blobId);
    }
    String filename =
        CanvasValidation.requireResourceName(downloadName, "downloadName")
            .replace('/', '_')
            .replace('\\', '_');
    if (filename.equals(".") || filename.equals("..")) {
      filename = "resource";
    }
    var attachment =
        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();
    return blobManager.presignOriginalUrl(blobId, attachment);
  }

  public StoragePresignedUrlDTO previewUrl(StudioPrincipal principal, UUID canvasId, UUID blobId) {
    requireHeldBlob(principal, canvasId, blobId);
    return blobManager.presignPreviewUrl(blobId);
  }

  private void requireHeldBlob(StudioPrincipal principal, UUID canvasId, UUID blobId) {
    resourceAccess.require(principal, Kind.CANVAS, canvasId);
    if (!queryService.containsHeldBlob(canvasId, blobId)) {
      throw ResourceAccess.notFound();
    }
  }
}
