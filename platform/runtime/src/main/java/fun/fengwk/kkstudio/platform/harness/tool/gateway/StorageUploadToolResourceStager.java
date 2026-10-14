package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.ByteArrayInputStream;
import java.util.Objects;

/** 通过统一 storage upload staging 生命周期准备工具资源。 */
public final class StorageUploadToolResourceStager implements ToolResourceStager {

  private final StorageUploadService uploadService;
  private final int maxBytes;

  public StorageUploadToolResourceStager(StorageUploadService uploadService, int maxBytes) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.maxBytes = maxBytes;
  }

  @Override
  public ResourceRef stage(String mediaType, String name, byte[] content) {
    StorageUploadService.StagedUpload upload =
        uploadService.stage(name, mediaType, new ByteArrayInputStream(content), maxBytes);
    return new ResourceRef(
        ResourceRef.blobUploadUri(upload.uploadId()),
        upload.mediaType(),
        upload.filename(),
        upload.sizeBytes(),
        upload.sha256());
  }

  @Override
  public void discard(ResourceRef resource) {
    Objects.requireNonNull(resource, "resource");
    if (resource.blobUploadId() == null) {
      throw new IllegalArgumentException("tool resource is not a staged upload");
    }
    try {
      uploadService.delete(resource.blobUploadId());
    } catch (RuntimeException ignored) {
      // READY upload 仍由 expiration 回收；放弃路径不能覆盖原始终态化结果。
    }
  }
}
