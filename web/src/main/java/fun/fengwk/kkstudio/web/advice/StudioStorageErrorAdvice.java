package fun.fengwk.kkstudio.web.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.web.controller.StudioStorageController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局 Blob 存储领域错误的 Web 层统一翻译器。
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} → 400（请求参数/声明不合法）
 *   <li>领域资源不存在 → 404（上传或 blob 不存在、blob 已 DELETING）
 *   <li>{@link StorageVerificationException} → 409（对象大小/校验和不匹配或对象缺失，上传保持 PENDING 可重传）
 *   <li>领域冲突 → 409（上传锁竞争，可重试），由通用领域 advice 处理
 * </ul>
 *
 * @author fengwk
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = StudioStorageController.class)
public class StudioStorageErrorAdvice {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(IllegalArgumentException error) {
    return build(HttpStatus.BAD_REQUEST, "STORAGE_VALIDATION", error.getMessage());
  }

  @ExceptionHandler(StorageVerificationException.class)
  public ResponseEntity<ApiResponse<Void>> handleVerification(StorageVerificationException error) {
    return build(HttpStatus.CONFLICT, "STORAGE_VERIFICATION", error.getMessage());
  }

  private ResponseEntity<ApiResponse<Void>> build(HttpStatus status, String code, String message) {
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("detail", message);
    return ResponseEntity.status(status)
        .body(ApiResponse.error(status, code, message, errorContext));
  }
}
