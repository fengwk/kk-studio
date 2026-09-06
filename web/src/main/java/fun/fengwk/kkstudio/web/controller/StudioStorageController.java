package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.util.UUID;

/**
 * 全局 Blob 存储 HTTP 边界。
 *
 * <p>统一返回 convention {@link Result}：reserve/complete/delete 管理上传契约，blob 端点签发原始/预览 GET 预签名 URL。
 * 所有响应不暴露 bucket 与对象物理 key。控制器总是注册；S3 未启用（SystemSettings.storageMedia.s3Enabled=false）时底层 bean
 * 不装配，请求确定性返回 503。
 *
 * @author fengwk
 */
@RestController
@RequestMapping("/api/storage")
public class StudioStorageController {

  private final ObjectProvider<StorageUploadService> uploadServices;
  private final ObjectProvider<StorageBlobManager> blobManagers;

  public StudioStorageController(
      ObjectProvider<StorageUploadService> uploadServices,
      ObjectProvider<StorageBlobManager> blobManagers) {
    this.uploadServices = uploadServices;
    this.blobManagers = blobManagers;
  }

  /** 预约上传：ACTIVE 内容命中返回 READY，否则返回 PENDING 与浏览器直传预签名 PUT。 */
  @PostMapping("/uploads")
  public Result<StorageUploadDTO> reserve(@RequestBody StorageUploadReserveRequestDTO request) {
    return Results.created(requireUploadService().reserve(request));
  }

  /** 完成上传：校验直传对象的大小与 SHA-256 后绑定 blob，返回 READY。 */
  @PostMapping("/uploads/{uploadId}/complete")
  public Result<StorageUploadDTO> complete(@PathVariable("uploadId") String uploadIdText) {
    return Results.ok(requireUploadService().complete(parseUuid(uploadIdText, "uploadId")));
  }

  /** 逻辑删除上传：事务内持久化 cleanup request，READY 同事务 release upload 引用；对象与行由 Storage Maintenance 异步回收。 */
  @DeleteMapping("/uploads/{uploadId}")
  public Result<Void> delete(@PathVariable("uploadId") String uploadIdText) {
    requireUploadService().delete(parseUuid(uploadIdText, "uploadId"));
    return Results.noContent();
  }

  /** 为 ACTIVE blob 的原始内容签发 GET 预签名 URL。 */
  @PostMapping("/blobs/{blobId}/download-url")
  public Result<StoragePresignedUrlDTO> downloadUrl(@PathVariable("blobId") String blobIdText) {
    return Results.ok(requireBlobManager().presignOriginalUrl(parseUuid(blobIdText, "blobId")));
  }

  /** 为 ACTIVE blob 的 webp 预览签发 GET 预签名 URL。 */
  @PostMapping("/blobs/{blobId}/preview-url")
  public Result<StoragePresignedUrlDTO> previewUrl(@PathVariable("blobId") String blobIdText) {
    return Results.ok(requireBlobManager().presignPreviewUrl(parseUuid(blobIdText, "blobId")));
  }

  private StorageUploadService requireUploadService() {
    StorageUploadService uploadService = uploadServices.getIfAvailable();
    if (uploadService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "global blob storage is disabled");
    }
    return uploadService;
  }

  private StorageBlobManager requireBlobManager() {
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "global blob storage is disabled");
    }
    return blobManager;
  }

  private static UUID parseUuid(String text, String name) {
    return WebDtoMapper.parseUuid(text, name);
  }
}
