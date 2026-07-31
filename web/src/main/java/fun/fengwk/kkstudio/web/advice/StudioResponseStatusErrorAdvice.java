package fun.fengwk.kkstudio.web.advice;

import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

import fun.fengwk.kkstudio.web.controller.StudioCanvasController;
import fun.fengwk.kkstudio.web.controller.StudioChatController;
import fun.fengwk.kkstudio.web.controller.StudioComfyuiRuntimeController;
import fun.fengwk.kkstudio.web.controller.StudioComfyuiWorkflowApiController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessObservabilityController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessRealtimeStreamPolicyController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessRetryPolicyController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessSessionController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessThreadController;
import fun.fengwk.kkstudio.web.controller.StudioInteractionController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime request-locale translation for controller-originated {@link ResponseStatusException}s.
 *
 * <p>The advice is restricted to Studio HTTP controllers. SSE requests are rethrown so the
 * convention handler keeps its existing stream-specific behavior.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
      StudioCanvasController.class,
      StudioChatController.class,
      StudioComfyuiRuntimeController.class,
      StudioComfyuiWorkflowApiController.class,
      StudioHarnessObservabilityController.class,
      StudioHarnessRealtimeStreamPolicyController.class,
      StudioHarnessRetryPolicyController.class,
      StudioHarnessSessionController.class,
      StudioHarnessThreadController.class,
      StudioInteractionController.class
    })
public class StudioResponseStatusErrorAdvice {

  private final StudioMessageService messageService;

  public StudioResponseStatusErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Result<Void>> handle(
      ResponseStatusException error, HttpServletRequest request) {
    if (isSseRequest(request)) {
      throw error;
    }

    int status = error.getStatusCode().value();
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("type", "about:blank");
    errorContext.put("title", messageService.httpTitle(status));
    errorContext.put("detail", error.getReason());
    ImmutableResolvedConventionErrorCode errorCode =
        new ImmutableResolvedConventionErrorCode(
            status,
            messageService.httpCode(status),
            messageService.httpMessage(status),
            errorContext);
    return ResponseEntity.status(status).body(Results.error(errorCode));
  }

  private static boolean isSseRequest(HttpServletRequest request) {
    if (request == null) {
      return false;
    }
    if (isSseMediaType(request.getContentType())
        || isSseMediaType(request.getHeader(HttpHeaders.ACCEPT))) {
      return true;
    }
    Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
    if (handler instanceof HandlerMethod handlerMethod) {
      RequestMapping mapping = handlerMethod.getMethodAnnotation(RequestMapping.class);
      return mapping != null
          && Arrays.stream(mapping.produces())
              .anyMatch(StudioResponseStatusErrorAdvice::isEventStreamMediaType);
    }
    return false;
  }

  private static boolean isSseMediaType(String value) {
    if (value == null) {
      return false;
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .map(mediaType -> mediaType.split(";", 2)[0].trim())
        .anyMatch(StudioResponseStatusErrorAdvice::isEventStreamMediaType);
  }

  private static boolean isEventStreamMediaType(String mediaType) {
    return MediaType.TEXT_EVENT_STREAM_VALUE.equalsIgnoreCase(mediaType);
  }
}
