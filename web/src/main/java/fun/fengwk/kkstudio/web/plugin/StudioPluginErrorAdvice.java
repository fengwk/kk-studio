package fun.fengwk.kkstudio.web.plugin;

import fun.fengwk.convention4j.api.code.HttpStatus;
import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.platform.error.AiDomainException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构建期 Plugin 管理面 REST 控制器错误处理器。
 *
 * <p>安全边界：严禁在响应或日志中回显认证凭据、明文 token 或敏感配置。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = StudioPluginController.class)
public class StudioPluginErrorAdvice {

  private static final String DEFAULT_READABLE_ERROR_DETAIL = "Failed to read request";

  private final StudioMessageService messageService;

  public StudioPluginErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(AiValidationException.class)
  public ResponseEntity<Result<Void>> handleValidation(AiValidationException error) {
    return build(HttpStatus.BAD_REQUEST, error);
  }

  @ExceptionHandler(AiResourceNotFoundException.class)
  public ResponseEntity<Result<Void>> handleNotFound(AiResourceNotFoundException error) {
    return build(HttpStatus.NOT_FOUND, error);
  }

  /** 期望坐标或凭据行 CAS 冲突：固定 409，响应体只含 resource 与冲突坐标，绝不回显秘密或请求正文。 */
  @ExceptionHandler(AiVersionConflictException.class)
  public ResponseEntity<Result<Void>> handleVersionConflict(AiVersionConflictException error) {
    return build(HttpStatus.CONFLICT, error);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Result<Void>> handleMessageNotReadable(
      HttpMessageNotReadableException error) {
    int status = HttpStatus.BAD_REQUEST.getStatus();
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("type", "about:blank");
    errorContext.put("title", messageService.httpTitle(status));
    // 固定去敏文案：Jackson 的 cause/message 可能内嵌请求正文片段（含秘密补丁值），绝不回显。
    errorContext.put("detail", DEFAULT_READABLE_ERROR_DETAIL);
    ImmutableResolvedConventionErrorCode code =
        new ImmutableResolvedConventionErrorCode(
            status,
            messageService.httpCode(status),
            messageService.httpMessage(status),
            errorContext);
    return ResponseEntity.status(status).body(Results.error(code));
  }

  private ResponseEntity<Result<Void>> build(HttpStatus status, AiDomainException error) {
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("resource", error.resource());
    errorContext.put("detail", error.getMessage());
    String message =
        messageService.domainMessage(error.code(), Map.of("resource", error.resource()));
    ImmutableResolvedConventionErrorCode code =
        new ImmutableResolvedConventionErrorCode(
            status.getStatus(), error.code().code(), message, errorContext);
    return ResponseEntity.status(status.getStatus()).body(Results.error(code));
  }
}
