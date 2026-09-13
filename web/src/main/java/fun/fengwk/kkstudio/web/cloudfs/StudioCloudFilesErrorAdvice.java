package fun.fengwk.kkstudio.web.cloudfs;

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

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudCycleException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudDirectoryNotEmptyException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditAmbiguousException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditPatternNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeAlreadyExistsException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeKindConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudVersionConflictException;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cloud File System REST 控制器全局异常通知处理器。
 *
 * <p>遵循严格的安全规范：绝不在错误消息或上下文结构中回显文件正文、{@code oldString}、检索正则表达式、认证令牌或机密。 CAS 冲突（409）仅透出版本/修订号对比与规范路径。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = StudioCloudFilesController.class)
public class StudioCloudFilesErrorAdvice {

  @ExceptionHandler(CloudVersionConflictException.class)
  public ResponseEntity<Result<Void>> handleVersionConflict(CloudVersionConflictException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    ctx.put("expectedVersion", String.valueOf(error.getExpectedVersion()));
    ctx.put("actualVersion", String.valueOf(error.getCurrentVersion()));
    return build(
        HttpStatus.CONFLICT,
        "CLOUD_VERSION_CONFLICT",
        "Version conflict for node at path: " + error.getPath().value(),
        ctx);
  }

  @ExceptionHandler(CloudRevisionConflictException.class)
  public ResponseEntity<Result<Void>> handleRevisionConflict(CloudRevisionConflictException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    ctx.put("expectedRevision", String.valueOf(error.getExpectedRevision()));
    ctx.put("actualRevision", String.valueOf(error.getCurrentRevision()));
    return build(
        HttpStatus.CONFLICT,
        "CLOUD_REVISION_CONFLICT",
        "Revision conflict for text file at path: " + error.getPath().value(),
        ctx);
  }

  @ExceptionHandler(CloudNodeAlreadyExistsException.class)
  public ResponseEntity<Result<Void>> handleAlreadyExists(CloudNodeAlreadyExistsException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    return build(
        HttpStatus.CONFLICT,
        "CLOUD_NODE_ALREADY_EXISTS",
        "Node already exists at path: " + error.getPath().value(),
        ctx);
  }

  @ExceptionHandler(CloudDirectoryNotEmptyException.class)
  public ResponseEntity<Result<Void>> handleDirectoryNotEmpty(
      CloudDirectoryNotEmptyException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    ctx.put("childCount", error.getChildCount());
    return build(
        HttpStatus.CONFLICT,
        "CLOUD_DIRECTORY_NOT_EMPTY",
        "Directory not empty at path: " + error.getPath().value(),
        ctx);
  }

  @ExceptionHandler(CloudNodeKindConflictException.class)
  public ResponseEntity<Result<Void>> handleKindConflict(CloudNodeKindConflictException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    return build(
        HttpStatus.CONFLICT,
        "CLOUD_NODE_KIND_CONFLICT",
        "Node kind conflict at path: " + error.getPath().value(),
        ctx);
  }

  @ExceptionHandler(CloudPathForbiddenException.class)
  public ResponseEntity<Result<Void>> handleForbidden(CloudPathForbiddenException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    return build(HttpStatus.FORBIDDEN, "CLOUD_PATH_FORBIDDEN", error.getMessage(), ctx);
  }

  @ExceptionHandler(CloudNodeNotFoundException.class)
  public ResponseEntity<Result<Void>> handleNotFound(CloudNodeNotFoundException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Node not found");
    return build(HttpStatus.NOT_FOUND, "CLOUD_NODE_NOT_FOUND", "Node not found", ctx);
  }

  @ExceptionHandler(StorageResourceNotFoundException.class)
  public ResponseEntity<Result<Void>> handleStorageNotFound(
      StorageResourceNotFoundException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", error.getMessage());
    return build(HttpStatus.NOT_FOUND, "STORAGE_RESOURCE_NOT_FOUND", error.getMessage(), ctx);
  }

  @ExceptionHandler(StorageVerificationException.class)
  public ResponseEntity<Result<Void>> handleStorageVerification(
      StorageVerificationException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", error.getMessage());
    return build(HttpStatus.CONFLICT, "STORAGE_VERIFICATION", error.getMessage(), ctx);
  }

  @ExceptionHandler(CloudEditPatternNotFoundException.class)
  public ResponseEntity<Result<Void>> handleEditPatternNotFound(
      CloudEditPatternNotFoundException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    return build(
        HttpStatus.BAD_REQUEST,
        "CLOUD_EDIT_PATTERN_NOT_FOUND",
        "Edit pattern not found at path: " + error.getPath().value(),
        ctx);
  }

  @ExceptionHandler(CloudEditAmbiguousException.class)
  public ResponseEntity<Result<Void>> handleEditAmbiguous(CloudEditAmbiguousException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("path", error.getPath().value());
    ctx.put("matchCount", error.getMatchCount());
    return build(
        HttpStatus.BAD_REQUEST,
        "CLOUD_EDIT_AMBIGUOUS",
        "Edit pattern is ambiguous (matches: "
            + error.getMatchCount()
            + ") at path: "
            + error.getPath().value(),
        ctx);
  }

  @ExceptionHandler(CloudCycleException.class)
  public ResponseEntity<Result<Void>> handleCycle(CloudCycleException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("sourcePath", error.getSourcePath().value());
    ctx.put("targetPath", error.getTargetPath().value());
    return build(
        HttpStatus.BAD_REQUEST,
        "CLOUD_CYCLE_DETECTED",
        "Cycle detected: cannot move directory into its descendant",
        ctx);
  }

  @ExceptionHandler({
    CloudFileSystemValidationException.class,
    CloudPathValidationException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<Result<Void>> handleValidation(RuntimeException error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", error.getMessage());
    return build(HttpStatus.BAD_REQUEST, "CLOUD_VALIDATION_ERROR", error.getMessage(), ctx);
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<Result<Void>> handleBinding(Exception error) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("detail", "Request validation failed");
    return build(
        HttpStatus.BAD_REQUEST, "CLOUD_VALIDATION_ERROR", "Request validation failed", ctx);
  }

  private ResponseEntity<Result<Void>> build(
      HttpStatus status, String code, String message, Map<String, Object> errorContext) {
    ImmutableResolvedConventionErrorCode errorCode =
        new ImmutableResolvedConventionErrorCode(status.getStatus(), code, message, errorContext);
    return ResponseEntity.status(status.getStatus()).body(Results.error(errorCode));
  }
}
