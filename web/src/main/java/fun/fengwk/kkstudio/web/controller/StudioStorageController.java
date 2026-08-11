package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;

import java.util.UUID;

/**
 * 全局 Blob 存储 HTTP 边界。
 *
 * <p>统一返回 convention {@link Result}：reserve/complete/delete 管理上传契约，blob 端点签发原始/预览 GET 预签名 URL。
 * 所有响应不暴露 bucket 与对象物理 key。仅在 {@code kk-studio.storage.s3.enabled=true} 时注册，避免未启用 S3 的部署因缺 bean
 * 而启动失败。
 *
 * @author fengwk
 */
@AllArgsConstructor
@ConditionalOnProperty(prefix = "kk-studio.storage.s3", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/storage")
public class StudioStorageController {

  private final StorageUploadService storageUploadService;
  private final StorageBlobManager storageBlobManager;

  /** 预约上传：ACTIVE 内容命中返回 READY，否则返回 PENDING 与浏览器直传预签名 PUT。 */
  @PostMapping("/uploads")
  public Result<StorageUploadDTO> reserve(@RequestBody StorageUploadReserveRequestDTO request) {
    return Results.ok(storageUploadService.reserve(request));
  }

  /** 完成上传：校验直传对象的大小与 SHA-256 后绑定 blob，返回 READY。 */
  @PostMapping("/uploads/{uploadId}/complete")
  public Result<StorageUploadDTO> complete(@PathVariable("uploadId") String uploadIdText) {
    return Results.ok(storageUploadService.complete(parseUuid(uploadIdText, "uploadId")));
  }

  /** 删除上传：PENDING 先删临时对象再删行；READY 同事务删行并 release blob（重复删除返回 404）。 */
  @DeleteMapping("/uploads/{uploadId}")
  public Result<Void> delete(@PathVariable("uploadId") String uploadIdText) {
    storageUploadService.delete(parseUuid(uploadIdText, "uploadId"));
    return Results.noContent();
  }

  /** 为 ACTIVE blob 的原始内容签发 GET 预签名 URL。 */
  @GetMapping("/blobs/{blobId}/presigned-original")
  public Result<StoragePresignedUrlDTO> presignBlobOriginal(
      @PathVariable("blobId") String blobIdText) {
    return Results.ok(storageBlobManager.presignOriginalUrl(parseUuid(blobIdText, "blobId")));
  }

  /** 为 ACTIVE blob 的 webp 预览签发 GET 预签名 URL。 */
  @GetMapping("/blobs/{blobId}/presigned-preview")
  public Result<StoragePresignedUrlDTO> presignBlobPreview(
      @PathVariable("blobId") String blobIdText) {
    return Results.ok(storageBlobManager.presignPreviewUrl(parseUuid(blobIdText, "blobId")));
  }

  private static UUID parseUuid(String text, String name) {
    try {
      UUID id = UUID.fromString(text);
      Assert.notNull(id, name + " must not be null");
      return id;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(name + " must be a valid UUID", e);
    }
  }
}
