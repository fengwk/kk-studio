package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * 全局 Blob 存储 HTTP 边界。
 *
 * <p>统一返回 convention {@link Result}：reserve/complete/delete 管理上传契约，blob 端点签发原始/预览 GET 预签名 URL。
 * 所有响应不暴露 bucket 与对象物理 key。
 *
 * @author fengwk
 */
@RestController
@RequestMapping("/api/storage")
public class StudioStorageController {

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;

  public StudioStorageController(
      StorageUploadService uploadService, StorageBlobManager blobManager) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /** 预约上传：ACTIVE 内容命中返回 READY，否则返回 PENDING 与浏览器直传预签名 PUT。 */
  @PostMapping("/uploads")
  public Result<StorageUploadDTO> reserve(@RequestBody StorageUploadReserveRequestDTO request) {
    return Results.created(uploadService.reserve(request));
  }

  /** 完成上传：校验直传对象的大小与 SHA-256 后绑定 blob，返回 READY。 */
  @PostMapping("/uploads/{uploadId}/complete")
  public Result<StorageUploadDTO> complete(@PathVariable("uploadId") String uploadIdText) {
    return Results.ok(uploadService.complete(parseUuid(uploadIdText, "uploadId")));
  }

  /** 逻辑删除上传：事务内持久化 cleanup request，READY 同事务 release upload 引用；对象与行由 Storage Maintenance 异步回收。 */
  @DeleteMapping("/uploads/{uploadId}")
  public Result<Void> delete(@PathVariable("uploadId") String uploadIdText) {
    uploadService.delete(parseUuid(uploadIdText, "uploadId"));
    return Results.noContent();
  }

  /** 为 ACTIVE blob 的原始内容签发 GET 预签名 URL。 */
  @PostMapping("/blobs/{blobId}/download-url")
  public Result<StoragePresignedUrlDTO> downloadUrl(@PathVariable("blobId") String blobIdText) {
    return Results.ok(blobManager.presignOriginalUrl(parseUuid(blobIdText, "blobId")));
  }

  /** 为 ACTIVE blob 的 webp 预览签发 GET 预签名 URL。 */
  @PostMapping("/blobs/{blobId}/preview-url")
  public Result<StoragePresignedUrlDTO> previewUrl(@PathVariable("blobId") String blobIdText) {
    return Results.ok(blobManager.presignPreviewUrl(parseUuid(blobIdText, "blobId")));
  }

  private static UUID parseUuid(String text, String name) {
    return WebDtoMapper.parseUuid(text, name);
  }
}
