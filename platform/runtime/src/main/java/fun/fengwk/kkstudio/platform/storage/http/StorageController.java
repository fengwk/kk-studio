package fun.fengwk.kkstudio.platform.storage.http;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 全局 Blob 存储 HTTP 边界。
 *
 * <p>reserve/complete/delete 管理上传契约，blob 端点签发原始/预览 GET 预签名 URL。所有响应不暴露 bucket 与对象物理 key。
 *
 * @author fengwk
 */
@RestController
@RequestMapping("/api/platform/storage")
public class StorageController {

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;

  public StorageController(StorageUploadService uploadService, StorageBlobManager blobManager) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /** 预约上传：ACTIVE 内容命中返回 READY，否则返回 PENDING 与浏览器直传预签名 PUT。 */
  @PostMapping("/uploads")
  @ResponseStatus(HttpStatus.CREATED)
  public StorageUploadDTO reserve(@RequestBody StorageUploadReserveRequestDTO request) {
    return translate(() -> uploadService.reserve(request));
  }

  /** 完成上传：校验直传对象的大小与 SHA-256 后绑定 blob，返回 READY。 */
  @PostMapping("/uploads/{uploadId}/complete")
  public StorageUploadDTO complete(@PathVariable("uploadId") UUID uploadId) {
    return translate(() -> uploadService.complete(uploadId));
  }

  /** 逻辑删除上传：事务内持久化 cleanup request，READY 同事务 release upload 引用；对象与行由 Storage Maintenance 异步回收。 */
  @DeleteMapping("/uploads/{uploadId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable("uploadId") UUID uploadId) {
    translate(
        () -> {
          uploadService.delete(uploadId);
          return null;
        });
  }

  /** 为 ACTIVE blob 的原始内容签发 GET 预签名 URL。 */
  @PostMapping("/blobs/{blobId}/download-url")
  public StoragePresignedUrlDTO downloadUrl(@PathVariable("blobId") UUID blobId) {
    return translate(() -> blobManager.presignOriginalUrl(blobId));
  }

  /** 为 ACTIVE blob 的 webp 预览签发 GET 预签名 URL。 */
  @PostMapping("/blobs/{blobId}/preview-url")
  public StoragePresignedUrlDTO previewUrl(@PathVariable("blobId") UUID blobId) {
    return translate(() -> blobManager.presignPreviewUrl(blobId));
  }

  /** 存储族的内部失败信号翻译成统一领域异常：对象校验失败 409，输入非法 400。 */
  private static <T> T translate(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (StorageVerificationException error) {
      throw DomainException.conflict("STORAGE_VERIFICATION", error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("storage", error.getMessage(), error);
    }
  }
}
