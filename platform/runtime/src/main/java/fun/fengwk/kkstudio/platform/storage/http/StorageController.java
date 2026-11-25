package fun.fengwk.kkstudio.platform.storage.http;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.util.Objects;
import java.util.UUID;

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

  public StorageController(StorageUploadService uploadService) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
  }

  /** 预约上传：ACTIVE 内容命中返回 READY，否则返回 PENDING 与浏览器直传预签名 PUT。 */
  @PostMapping("/uploads")
  @ResponseStatus(HttpStatus.CREATED)
  public StorageUploadDTO reserve(
      @RequestBody StorageUploadReserveRequestDTO request,
      @RequestAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE) StudioPrincipal principal) {
    return uploadService.reserve(request, principal.userId());
  }

  /** 完成上传：校验直传对象的大小与 SHA-256 后绑定 blob，返回 READY。 */
  @PostMapping("/uploads/{uploadId}/complete")
  public StorageUploadDTO complete(
      @PathVariable("uploadId") UUID uploadId,
      @RequestAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE) StudioPrincipal principal) {
    return uploadService.complete(uploadId, principal.userId());
  }

  /** 逻辑删除上传：事务内持久化 cleanup request，READY 同事务 release upload 引用；对象与行由 Storage Maintenance 异步回收。 */
  @DeleteMapping("/uploads/{uploadId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable("uploadId") UUID uploadId,
      @RequestAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE) StudioPrincipal principal) {
    uploadService.delete(uploadId, principal.userId());
  }
}
