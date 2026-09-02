package fun.fengwk.kkstudio.web.advice;

import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessRequestFormatException;
import fun.fengwk.kkstudio.web.controller.StudioCanvasController;
import fun.fengwk.kkstudio.web.controller.StudioChatController;
import fun.fengwk.kkstudio.web.controller.StudioComfyuiRuntimeController;
import fun.fengwk.kkstudio.web.controller.StudioComfyuiWorkflowApiController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessCommandBatchController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessSessionController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessThreadController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/** 对 controller 抛出的 {@link ResponseStatusException} 以及请求体反序列化异常做请求级 locale 的统一包装。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
      StudioCanvasController.class,
      StudioChatController.class,
      StudioComfyuiRuntimeController.class,
      StudioComfyuiWorkflowApiController.class,
      StudioHarnessCommandBatchController.class,
      StudioHarnessSessionController.class,
      StudioHarnessThreadController.class
    })
public class StudioResponseStatusErrorAdvice {

  private static final String DEFAULT_READABLE_ERROR_DETAIL = "Failed to read request";

  private final StudioMessageService messageService;

  public StudioResponseStatusErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Result<Void>> handle(
      ResponseStatusException error, HttpServletRequest request) {
    int status = error.getStatusCode().value();
    String reason =
        error.getCause() instanceof HarnessRuntimeConflictException conflict
            ? conflict.reason().name()
            : null;
    return buildResponse(status, error.getReason(), reason);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Result<Void>> handle(
      HttpMessageNotReadableException error, HttpServletRequest request) {
    int status = HttpStatus.BAD_REQUEST.value();
    return buildResponse(status, extractHarnessFormatDetail(error), null);
  }

  private static String extractHarnessFormatDetail(HttpMessageNotReadableException error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof HarnessRequestFormatException) {
        String message = cause.getMessage();
        if (message != null && !message.isBlank()) {
          return message;
        }
      }
    }
    return DEFAULT_READABLE_ERROR_DETAIL;
  }

  private ResponseEntity<Result<Void>> buildResponse(int status, String detail, String reason) {
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("type", "about:blank");
    errorContext.put("title", messageService.httpTitle(status));
    errorContext.put("detail", detail);
    if (reason != null) {
      errorContext.put("reason", reason);
    }
    ImmutableResolvedConventionErrorCode errorCode =
        new ImmutableResolvedConventionErrorCode(
            status,
            messageService.httpCode(status),
            messageService.httpMessage(status),
            errorContext);
    return ResponseEntity.status(status).body(Results.error(errorCode));
  }
}
