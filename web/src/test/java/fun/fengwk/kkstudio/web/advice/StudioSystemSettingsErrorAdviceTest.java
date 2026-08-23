package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fun.fengwk.convention4j.api.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsVersionConflictException;
import fun.fengwk.kkstudio.web.controller.StudioSystemSettingsController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.Locale;
import java.util.Map;

class StudioSystemSettingsErrorAdviceTest {

  @AfterEach
  void resetLocaleContext() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void scopesAdviceToSystemSettingsControllerOnly() {
    RestControllerAdvice advice =
        StudioSystemSettingsErrorAdvice.class.getAnnotation(RestControllerAdvice.class);
    assertArrayEquals(
        new Class<?>[] {StudioSystemSettingsController.class}, advice.assignableTypes());
  }

  @Test
  void returnsTypedCodeAndUsefulErrorContext() {
    StudioSystemSettingsErrorAdvice advice =
        new StudioSystemSettingsErrorAdvice(new StudioMessageService());

    LocaleContextHolder.setLocale(Locale.US);
    ResponseEntity<Result<Void>> validation =
        advice.handleValidation(
            new SystemSettingsValidationException("system_settings", "tool is required"));
    assertEquals(400, validation.getStatusCode().value());
    assertNotNull(validation.getBody());
    Result<Void> validationBody = validation.getBody();
    assertEquals("validation", validationBody.getCode());
    assertEquals("Invalid system_settings request.", validationBody.getMessage());
    assertEquals(
        Map.of("resource", "system_settings", "detail", "tool is required"),
        validationBody.getErrors());

    ResponseEntity<Result<Void>> notFound =
        advice.handleNotFound(
            new SystemSettingsResourceNotFoundException("system_settings", "row is missing"));
    assertEquals(404, notFound.getStatusCode().value());
    assertEquals("resource_not_found", notFound.getBody().getCode());

    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    ResponseEntity<Result<Void>> conflict =
        advice.handleVersionConflict(
            new SystemSettingsVersionConflictException("system_settings", "3", "4"));
    assertEquals(409, conflict.getStatusCode().value());
    assertNotNull(conflict.getBody());
    Result<Void> conflictBody = conflict.getBody();
    assertEquals("version_conflict", conflictBody.getCode());
    assertEquals("system_settings 已被其他请求修改。", conflictBody.getMessage());
    assertEquals(
        Map.of(
            "resource",
            "system_settings",
            "reason",
            "VERSION_CONFLICT",
            "expectedVersion",
            "3",
            "actualVersion",
            "4",
            "detail",
            "system settings version conflict: expected=3 actual=4"),
        conflictBody.getErrors());
  }
}
