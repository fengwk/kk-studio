package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ToolOutputLimit;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.function.IntSupplier;

/** 通过统一 storage upload staging 生命周期准备工具资源。 */
@Slf4j
public final class StorageUploadToolResourceStager implements ToolResourceStager {

  private final StorageUploadService uploadService;
  private final IntSupplier maxBytes;

  public StorageUploadToolResourceStager(StorageUploadService uploadService, int maxBytes) {
    this(uploadService, () -> maxBytes);
  }

  public StorageUploadToolResourceStager(StorageUploadService uploadService, IntSupplier maxBytes) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.maxBytes = Objects.requireNonNull(maxBytes, "maxBytes");
  }

  @Override
  public ResourceRef stage(String mediaType, String name, byte[] content) {
    int limitBytes = maxBytes.getAsInt();
    if (content.length > limitBytes) {
      throw new ToolOutputLimitExceededException(
          ToolOutputLimit.exceededMessage(content.length, limitBytes));
    }
    StorageUploadService.StagedUpload upload =
        uploadService.stage(name, mediaType, new ByteArrayInputStream(content), limitBytes);
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
    } catch (RuntimeException failure) {
      // READY upload 仍由 expiration 回收；放弃路径不能覆盖原始终态化结果。
      log.warn(
          "cannot discard staged tool resource upload {}: {}",
          resource.blobUploadId(),
          failure.getClass().getSimpleName());
    }
  }
}
