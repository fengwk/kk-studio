package fun.fengwk.kkstudio.web.advice;

import fun.fengwk.convention4j.api.code.HttpStatus;
import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsDomainException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsVersionConflictException;
import fun.fengwk.kkstudio.web.controller.StudioSystemSettingsController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * System settings 领域错误模型的 Web 层统一翻译器。
 *
 * <p>每个 handler 都返回项目 {@link Result} 信封，其中包含由 {@link
 * fun.fengwk.kkstudio.platform.settings.SystemSettingsErrorCode} 派生的稳定机器可读 {@code code}。HTTP 状态：
 * 校验失败 → 400，行缺失 → 404，expectedVersion CAS 竞争 → 409。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = StudioSystemSettingsController.class)
public class StudioSystemSettingsErrorAdvice {

  private final StudioMessageService messageService;

  public StudioSystemSettingsErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(SystemSettingsValidationException.class)
  public ResponseEntity<Result<Void>> handleValidation(SystemSettingsValidationException error) {
    return build(HttpStatus.BAD_REQUEST, error);
  }

  @ExceptionHandler(SystemSettingsResourceNotFoundException.class)
  public ResponseEntity<Result<Void>> handleNotFound(
      SystemSettingsResourceNotFoundException error) {
    return build(HttpStatus.NOT_FOUND, error);
  }

  @ExceptionHandler(SystemSettingsVersionConflictException.class)
  public ResponseEntity<Result<Void>> handleVersionConflict(
      SystemSettingsVersionConflictException error) {
    return build(HttpStatus.CONFLICT, error);
  }

  private ResponseEntity<Result<Void>> build(
      HttpStatus status, SystemSettingsDomainException error) {
    Map<String, Object> errorContext = new LinkedHashMap<>();
    errorContext.put("resource", error.resource());
    if (error instanceof SystemSettingsVersionConflictException versionConflict) {
      errorContext.put("reason", "VERSION_CONFLICT");
      errorContext.put("expectedVersion", versionConflict.expectedVersion());
      errorContext.put("actualVersion", versionConflict.actualVersion());
    }
    errorContext.put("detail", error.getMessage());
    String message =
        messageService.message(
            "studio.error.domain." + error.code().code() + ".message",
            Map.of("resource", error.resource()));
    ImmutableResolvedConventionErrorCode code =
        new ImmutableResolvedConventionErrorCode(
            status.getStatus(), error.code().code(), message, errorContext);
    return ResponseEntity.status(status.getStatus()).body(Results.error(code));
  }
}
