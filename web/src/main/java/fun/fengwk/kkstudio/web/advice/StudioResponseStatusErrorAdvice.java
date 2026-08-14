package fun.fengwk.kkstudio.web.advice;

import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.web.controller.StudioCanvasController;
import fun.fengwk.kkstudio.web.controller.StudioChatController;
import fun.fengwk.kkstudio.web.controller.StudioComfyuiRuntimeController;
import fun.fengwk.kkstudio.web.controller.StudioComfyuiWorkflowApiController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessThreadController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/** 对 controller 抛出的 {@link ResponseStatusException} 做请求级 locale 的运行时翻译。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
      StudioCanvasController.class,
      StudioChatController.class,
      StudioComfyuiRuntimeController.class,
      StudioComfyuiWorkflowApiController.class,
      StudioHarnessThreadController.class
    })
public class StudioResponseStatusErrorAdvice {

  private final StudioMessageService messageService;

  public StudioResponseStatusErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Result<Void>> handle(
      ResponseStatusException error, HttpServletRequest request) {
    int status = error.getStatusCode().value();
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("type", "about:blank");
    errorContext.put("title", messageService.httpTitle(status));
    errorContext.put("detail", error.getReason());
    if (error.getCause() instanceof HarnessRuntimeConflictException conflict) {
      errorContext.put("reason", conflict.reason().name());
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
