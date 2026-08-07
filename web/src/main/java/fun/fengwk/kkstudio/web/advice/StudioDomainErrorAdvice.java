package fun.fengwk.kkstudio.web.advice;

import fun.fengwk.convention4j.api.code.HttpStatus;
import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import fun.fengwk.kkstudio.core.ai.error.AiDomainException;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.web.controller.StudioAgentDefinitionController;
import fun.fengwk.kkstudio.web.controller.StudioAgentModelController;
import fun.fengwk.kkstudio.web.controller.StudioAgentProviderController;
import fun.fengwk.kkstudio.web.controller.StudioChatController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI catalog domain 错误模型的 Web 层统一翻译器。
 *
 * <p>每个 handler 都返回项目 {@link Result} 信封，其中包含由类型化错误的 {@link
 * fun.fengwk.kkstudio.core.ai.error.DomainErrorCode} 派生的稳定机器可读 {@code code}。HTTP 状态遵循以下语义：
 *
 * <ul>
 *   <li>{@link AiValidationException}, {@link MissingServletRequestParameterException}, {@link
 *       MethodArgumentTypeMismatchException} → 400
 *   <li>{@link AiResourceNotFoundException} → 404
 *   <li>{@link AiVersionConflictException}, {@link AiDuplicateException}, {@link AiInUseException}
 *       → 409
 * </ul>
 *
 * <p>数据库唯一性 / 完整性竞争条件有意不在此处翻译：四个 catalog 服务自行包装 {@link
 * org.springframework.dao.DuplicateKeyException} 并上报类型化错误，因此全局 advice 不会影响无关 controller。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
      StudioAgentProviderController.class,
      StudioAgentModelController.class,
      StudioAgentDefinitionController.class,
      StudioChatController.class
    })
public class StudioDomainErrorAdvice {

  private final StudioMessageService messageService;

  public StudioDomainErrorAdvice(StudioMessageService messageService) {
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

  @ExceptionHandler(AiVersionConflictException.class)
  public ResponseEntity<Result<Void>> handleVersionConflict(AiVersionConflictException error) {
    return build(HttpStatus.CONFLICT, error);
  }

  @ExceptionHandler(AiDuplicateException.class)
  public ResponseEntity<Result<Void>> handleDuplicate(AiDuplicateException error) {
    return build(HttpStatus.CONFLICT, error);
  }

  @ExceptionHandler(AiInUseException.class)
  public ResponseEntity<Result<Void>> handleInUse(AiInUseException error) {
    return build(HttpStatus.CONFLICT, error);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Result<Void>> handleMissingParam(
      MissingServletRequestParameterException error) {
    AiValidationException wrapped =
        new AiValidationException(
            error.getParameterName(), error.getParameterName() + " is required");
    return build(
        HttpStatus.BAD_REQUEST,
        wrapped,
        messageService.validationRequired(error.getParameterName()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Result<Void>> handleTypeMismatch(
      MethodArgumentTypeMismatchException error) {
    AiValidationException wrapped =
        new AiValidationException(
            error.getName(), error.getName() + " has invalid value: " + error.getValue());
    return build(
        HttpStatus.BAD_REQUEST, wrapped, messageService.validationTypeMismatch(error.getName()));
  }

  private ResponseEntity<Result<Void>> build(HttpStatus status, AiDomainException error) {
    return build(
        status,
        error,
        messageService.domainMessage(error.code(), Map.of("resource", error.resource())));
  }

  private ResponseEntity<Result<Void>> build(
      HttpStatus status, AiDomainException error, String message) {
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("resource", error.resource());
    if (error instanceof AiVersionConflictException versionConflict) {
      errorContext.put("expectedVersion", versionConflict.expectedVersion());
      errorContext.put("actualVersion", versionConflict.actualVersion());
    }
    errorContext.put("detail", error.getMessage());
    ImmutableResolvedConventionErrorCode code =
        new ImmutableResolvedConventionErrorCode(
            status.getStatus(), error.code().code(), message, errorContext);
    return ResponseEntity.status(status.getStatus()).body(Results.error(code));
  }
}
