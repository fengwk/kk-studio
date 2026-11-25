package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * Platform boundary for consuming user uploads as Canvas Resource owners.
 *
 * <p>Implementations must join the caller's transaction. The caller locks upload facts before
 * planning, retains one Blob reference for every new Canvas Resource, and only then deletes each
 * upload owner.
 */
public interface CanvasUploadOwnership {

  ReadyUpload lockReady(UUID uploadId, UUID userId);

  void retain(UUID blobId);

  void delete(UUID uploadId, UUID userId);

  record ReadyUpload(UUID blobId, String filename) {
    public ReadyUpload {
      Objects.requireNonNull(blobId, "blobId");
      Objects.requireNonNull(filename, "filename");
    }
  }
}
