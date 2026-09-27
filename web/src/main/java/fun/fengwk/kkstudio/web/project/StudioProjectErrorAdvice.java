package fun.fengwk.kkstudio.web.project;

import fun.fengwk.convention4j.api.code.HttpStatus;
import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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

  @ExceptionHandler(ProjectVersionConflictException.class)
  public ResponseEntity<Result<Void>> handleVersionConflict(ProjectVersionConflictException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("resource", error.resource());
    ctx.put("expectedVersion", error.expectedVersion());
    ctx.put("actualVersion", error.actualVersion());
    return build(
        HttpStatus.CONFLICT,
        "PROJECT_VERSION_CONFLICT",
        "Version conflict for " + error.resource(),
        ctx);
  }

  @ExceptionHandler(ProjectDuplicateException.class)
  public ResponseEntity<Result<Void>> handleDuplicate(ProjectDuplicateException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("resource", error.resource());
    ctx.put("detail", error.getMessage());
    return build(HttpStatus.CONFLICT, "PROJECT_DUPLICATE_CONFLICT", error.getMessage(), ctx);
  }

  @ExceptionHandler(HarnessRuntimeConflictException.class)
  public ResponseEntity<Result<Void>> handleHarnessConflict(HarnessRuntimeConflictException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Issue runtime conflict occurred");
    return build(
        HttpStatus.CONFLICT, "PROJECT_RUNTIME_CONFLICT", "Issue runtime conflict occurred", ctx);
  }

  @ExceptionHandler(ProjectNotFoundException.class)
  public ResponseEntity<Result<Void>> handleResourceNotFound(ProjectNotFoundException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("resource", error.resource());
    return build(
        HttpStatus.NOT_FOUND,
        "PROJECT_RESOURCE_NOT_FOUND",
        "Resource not found: " + error.resource(),
        ctx);
  }

  @ExceptionHandler(HarnessRuntimeNotFoundException.class)
  public ResponseEntity<Result<Void>> handleHarnessNotFound(HarnessRuntimeNotFoundException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Issue runtime resource not found");
    return build(
        HttpStatus.NOT_FOUND, "PROJECT_RUNTIME_NOT_FOUND", "Issue runtime resource not found", ctx);
  }

  @ExceptionHandler({ProjectValidationException.class, IllegalArgumentException.class})
  public ResponseEntity<Result<Void>> handleValidation(RuntimeException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", error.getMessage());
    return build(HttpStatus.BAD_REQUEST, "PROJECT_VALIDATION_ERROR", error.getMessage(), ctx);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Project operation failed");
    return build(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "PROJECT_INTERNAL_ERROR",
        "Project operation failed",
        ctx);
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<Result<Void>> handleBinding(Exception error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Request validation failed");
    return build(
        HttpStatus.BAD_REQUEST, "PROJECT_VALIDATION_ERROR", "Request validation failed", ctx);
  }

  private ResponseEntity<Result<Void>> build(
      HttpStatus status, String code, String message, Map<String, Object> errorContext) {
    ImmutableResolvedConventionErrorCode errorCode =
        new ImmutableResolvedConventionErrorCode(status.getStatus(), code, message, errorContext);
    return ResponseEntity.status(status.getStatus()).body(Results.error(errorCode));
  }
}
