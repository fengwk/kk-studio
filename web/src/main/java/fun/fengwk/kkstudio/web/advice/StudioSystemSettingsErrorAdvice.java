package fun.fengwk.kkstudio.web.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsDomainException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsVersionConflictException;
import fun.fengwk.kkstudio.web.api.ApiResponse;
import fun.fengwk.kkstudio.web.controller.StudioConfigSyncController;
import fun.fengwk.kkstudio.web.controller.StudioSystemSettingsController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * System settings 领域错误模型的 Web 层统一翻译器。
 *
 * <p>每个 handler 都返回项目 {@link ApiResponse} 信封，其中包含由 {@link
 * fun.fengwk.kkstudio.platform.settings.SystemSettingsErrorCode} 派生的稳定机器可读 {@code code}。HTTP 状态：
 * 校验失败 → 400，行缺失 → 404，expectedVersion CAS 竞争 → 409。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {StudioSystemSettingsController.class, StudioConfigSyncController.class})
public class StudioSystemSettingsErrorAdvice {

  private final StudioMessageService messageService;

  public StudioSystemSettingsErrorAdvice(StudioMessageService messageService) {
    this.messageService = messageService;
  }

  @ExceptionHandler(SystemSettingsValidationException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(
      SystemSettingsValidationException error) {
    return build(HttpStatus.BAD_REQUEST, error);
  }

  @ExceptionHandler(SystemSettingsResourceNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotFound(
      SystemSettingsResourceNotFoundException error) {
    return build(HttpStatus.NOT_FOUND, error);
  }

  @ExceptionHandler(SystemSettingsVersionConflictException.class)
  public ResponseEntity<ApiResponse<Void>> handleVersionConflict(
      SystemSettingsVersionConflictException error) {
    return build(HttpStatus.CONFLICT, error);
  }

  private ResponseEntity<ApiResponse<Void>> build(
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
            Map.of("resource", messageService.resourceDisplayName(error.resource())));
    return ResponseEntity.status(status)
        .body(ApiResponse.error(status, error.code().code(), message, errorContext));
  }
}
