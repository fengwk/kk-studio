package fun.fengwk.kkstudio.web.project;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.web.api.ApiResponse;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project / Issue REST 控制器全局统一错误处理器。
 *
 * <p>安全契约：严禁在响应或日志中回显 Issue Activity 正文、幂等键、认证凭证或非预期的敏感属性。409 冲突响应仅透出资源类型以及期望与实际版本对比，协助前端判断 CAS 重试。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {StudioProjectController.class, StudioIssueController.class})
public class StudioProjectErrorAdvice {

  private final StudioMessageService messageService;

  public StudioProjectErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(ProjectVersionConflictException.class)
  public ResponseEntity<ApiResponse<Void>> handleVersionConflict(
      ProjectVersionConflictException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("resource", error.resource());
    ctx.put("expectedVersion", error.expectedVersion());
    ctx.put("actualVersion", error.actualVersion());
    return build(
        HttpStatus.CONFLICT,
        "PROJECT_VERSION_CONFLICT",
        messageService.message(
            "studio.error.project.version_conflict.message",
            Map.of("resource", messageService.resourceDisplayName(error.resource()))),
        ctx);
  }

  @ExceptionHandler(ProjectDuplicateException.class)
  public ResponseEntity<ApiResponse<Void>> handleDuplicate(ProjectDuplicateException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("resource", error.resource());
    ctx.put("detail", error.getMessage());
    return build(HttpStatus.CONFLICT, "PROJECT_DUPLICATE_CONFLICT", error.getMessage(), ctx);
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

  @ExceptionHandler(ProjectNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ProjectNotFoundException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("resource", error.resource());
    return build(
        HttpStatus.NOT_FOUND,
        "PROJECT_RESOURCE_NOT_FOUND",
        messageService.message(
            "studio.error.project.resource_not_found.message",
            Map.of("resource", messageService.resourceDisplayName(error.resource()))),
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

  @ExceptionHandler({ProjectValidationException.class, IllegalArgumentException.class})
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
