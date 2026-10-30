package fun.fengwk.kkstudio.web.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.web.api.ApiResponse;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * 未被更具体 advice 处理的请求异常兜底翻译。
 *
 * <p>本 advice 只把异常转换为项目 {@link ApiResponse}，绝不包装成功返回值、下载、HTML、裸流或任意 controller 的正常响应；资源已提交/客户端断连仍由
 * {@link StudioDisconnectedClientExceptionResolver} 先行隔离。
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class StudioFallbackErrorAdvice {

  private static final String DEFAULT_READABLE_ERROR_DETAIL = "Failed to read request";

  private final StudioMessageService messageService;

  public StudioFallbackErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> handle(ResponseStatusException error) {
    return build(error.getStatusCode().value(), error.getReason());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handle(HttpMessageNotReadableException error) {
    // 固定去敏文案：反序列化异常链可能内嵌请求正文，不能透出 parser 细节。
    return build(HttpStatus.BAD_REQUEST.value(), DEFAULT_READABLE_ERROR_DETAIL);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception error) {
    if (error instanceof ErrorResponse errorResponse) {
      // NoResourceFoundException、MethodArgumentNotValidException 等仍保留 Spring 判定的 HTTP 状态。
      return build(errorResponse.getStatusCode().value(), errorResponse.getBody().getDetail());
    }
    return build(HttpStatus.INTERNAL_SERVER_ERROR.value(), null);
  }

  private ResponseEntity<ApiResponse<Void>> build(int status, String detail) {
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("type", "about:blank");
    errorContext.put("title", httpTitle(status));
    if (detail != null) {
      errorContext.put("detail", detail);
    }
    return ResponseEntity.status(status)
        .body(
            ApiResponse.error(
                HttpStatusCode.valueOf(status),
                httpCode(status),
                httpMessage(status),
                errorContext));
  }

  private String httpCode(int status) {
    return messageService.httpCode(status);
  }

  private String httpMessage(int status) {
    try {
      return messageService.httpMessage(status);
    } catch (MissingResourceException missing) {
      return messageService.message("studio.error.http.unknown.message", Map.of("status", status));
    }
  }

  private String httpTitle(int status) {
    try {
      return messageService.httpTitle(status);
    } catch (MissingResourceException missing) {
      return messageService.message("studio.error.http.unknown.title", Map.of());
    }
  }
}
