package fun.fengwk.kkstudio.core.studio.resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasUpload;
import fun.fengwk.kkstudio.studio.canvas.CanvasUploadRepository;

import java.util.Optional;

/** Resource 插入与 Upload 删除的短事务边界。 */
@Component
public class CanvasResourceCommitter {

  private final CanvasResourceRepository resourceRepository;
  private final CanvasUploadRepository uploadRepository;

  public CanvasResourceCommitter(
      CanvasResourceRepository resourceRepository, CanvasUploadRepository uploadRepository) {
    this.resourceRepository = resourceRepository;
    this.uploadRepository = uploadRepository;
  }

  @Transactional
  public CanvasResource commitUpload(CanvasUpload expectedUpload, CanvasResource candidate) {
    Optional<CanvasUpload> lockedUpload =
        uploadRepository.findByIdForUpdate(expectedUpload.canvasId(), expectedUpload.id());
    if (lockedUpload.isEmpty()) {
      return resourceRepository
          .findById(candidate.canvasId(), candidate.id())
          .orElseThrow(
              () ->
                  new CanvasResourceStorageException(
                      CanvasResourceStorageException.Reason.NOT_FOUND, "Canvas upload not found"));
    }
    CanvasUpload locked = lockedUpload.get();
    if (!locked.equals(expectedUpload)) {
      throw new IllegalStateException("Canvas upload changed during finalize");
    }

    resourceRepository.addIfAbsent(candidate);
    CanvasResource result = requireSameCanvasResource(candidate.canvasId(), candidate.id());
    if (!uploadRepository.delete(expectedUpload.canvasId(), expectedUpload.id())) {
      throw new IllegalStateException("Canvas upload disappeared during finalize");
    }
    return result;
  }

  @Transactional
  public CanvasResource commitMaterialized(CanvasResource candidate) {
    resourceRepository.addIfAbsent(candidate);
    return requireSameCanvasResource(candidate.canvasId(), candidate.id());
  }

  private CanvasResource requireSameCanvasResource(long canvasId, long resourceId) {
    return resourceRepository
        .findById(resourceId)
        .map(resource -> requireSameCanvas(resource, canvasId))
        .orElseThrow(
            () -> new IllegalStateException("Canvas Resource insert race lost without row"));
  }

  private CanvasResource requireSameCanvas(CanvasResource resource, long canvasId) {
    if (resource.canvasId() != canvasId) {
      throw new IllegalArgumentException("resourceId already belongs to another canvas");
    }
    return resource;
  }
}
