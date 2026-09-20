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
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
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
  private static final String CODE_PLUGIN_KEY_UNAVAILABLE = "plugin_key_unavailable";
  private static final String DEFAULT_PLUGIN_KEY_UNAVAILABLE_MESSAGE =
      "Plugin credential key is unavailable";

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

  @ExceptionHandler(PluginKeyUnavailableException.class)
  public ResponseEntity<Result<Void>> handlePluginKeyUnavailable(
      PluginKeyUnavailableException error) {
    int status = HttpStatus.SERVICE_UNAVAILABLE.getStatus();
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("resource", "plugin");
    // 只返回固定去敏文案：异常消息可能携带主密钥文件路径等部署细节，不属于调用方可见信息。
    errorContext.put("detail", DEFAULT_PLUGIN_KEY_UNAVAILABLE_MESSAGE);
    ImmutableResolvedConventionErrorCode code =
        new ImmutableResolvedConventionErrorCode(
            status,
            CODE_PLUGIN_KEY_UNAVAILABLE,
            DEFAULT_PLUGIN_KEY_UNAVAILABLE_MESSAGE,
            errorContext);
    return ResponseEntity.status(status).body(Results.error(code));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Result<Void>> handleMessageNotReadable(
      HttpMessageNotReadableException error) {
    int status = HttpStatus.BAD_REQUEST.getStatus();
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("type", "about:blank");
    errorContext.put("title", messageService.httpTitle(status));
    errorContext.put("detail", extractReadableDetail(error));
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

  private static String extractReadableDetail(HttpMessageNotReadableException error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof IllegalArgumentException illegalArgumentException) {
        String message = illegalArgumentException.getMessage();
        if (message != null && !message.isBlank()) {
          return message;
        }
      }
    }
    String message = error.getMessage();
    return message != null && !message.isBlank() ? message : DEFAULT_READABLE_ERROR_DETAIL;
  }
}
