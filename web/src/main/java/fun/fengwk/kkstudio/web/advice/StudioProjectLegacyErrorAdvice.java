package fun.fengwk.kkstudio.web.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.project.http.IssueController;
import fun.fengwk.kkstudio.project.http.ProjectController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project / Issue REST 控制器全局统一错误处理器。
 *
 * <p>安全契约：严禁在响应或日志中回显 Issue Activity 正文、幂等键、认证凭证或非预期的敏感属性。409 冲突响应仅透出资源类型以及期望与实际版本对比，协助前端判断 CAS 重试。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ProjectController.class, IssueController.class})
public class StudioProjectLegacyErrorAdvice {

  private final StudioMessageService messageService;

  public StudioProjectLegacyErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(HarnessRuntimeConflictException.class)
  public ResponseEntity<ApiResponse<Void>> handleHarnessConflict(
      HarnessRuntimeConflictException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Issue runtime conflict occurred");
    return build(
        HttpStatus.CONFLICT,
        "PROJECT_RUNTIME_CONFLICT",
        messageService.message("studio.error.project.runtime_conflict.message", Map.of()),
        ctx);
  }

  @ExceptionHandler(HarnessRuntimeNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleHarnessNotFound(
      HarnessRuntimeNotFoundException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Issue runtime resource not found");
    return build(
        HttpStatus.NOT_FOUND,
        "PROJECT_RUNTIME_NOT_FOUND",
        messageService.message("studio.error.project.runtime_not_found.message", Map.of()),
        ctx);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(RuntimeException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", error.getMessage());
    return build(
        HttpStatus.BAD_REQUEST,
        "PROJECT_VALIDATION_ERROR",
        messageService.httpMessage(HttpStatus.BAD_REQUEST.value()),
        ctx);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Project operation failed");
    return build(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "PROJECT_INTERNAL_ERROR",
        messageService.message("studio.error.project.internal_error.message", Map.of()),
        ctx);
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<ApiResponse<Void>> handleBinding(Exception error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Request validation failed");
    return build(
        HttpStatus.BAD_REQUEST,
        "PROJECT_VALIDATION_ERROR",
        messageService.httpMessage(HttpStatus.BAD_REQUEST.value()),
        ctx);
  }

  private ResponseEntity<ApiResponse<Void>> build(
      HttpStatus status, String code, String message, Map<String, Object> errorContext) {
    return ResponseEntity.status(status)
        .body(ApiResponse.error(status, code, message, errorContext));
  }
}
