package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fun.fengwk.convention4j.api.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.web.controller.StudioAgentDefinitionController;
import fun.fengwk.kkstudio.web.controller.StudioAgentModelController;
import fun.fengwk.kkstudio.web.controller.StudioAgentProviderController;
import fun.fengwk.kkstudio.web.controller.StudioChatController;
import fun.fengwk.kkstudio.web.controller.StudioEnvironmentController;
import fun.fengwk.kkstudio.web.controller.StudioMcpServerController;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.Locale;
import java.util.Map;

class StudioDomainErrorAdviceTest {

  @AfterEach
  void resetLocaleContext() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void scopesAdviceToSupportedControllers() {
    RestControllerAdvice advice =
        StudioDomainErrorAdvice.class.getAnnotation(RestControllerAdvice.class);

    assertArrayEquals(
        new Class<?>[] {
          StudioAgentProviderController.class,
          StudioAgentModelController.class,
          StudioAgentDefinitionController.class,
          StudioChatController.class,
          StudioMcpServerController.class,
          StudioEnvironmentController.class
        },
        advice.assignableTypes());
  }

  @Test
  void returnsTypedCodeAndUsefulErrorContext() {
    StudioDomainErrorAdvice advice = new StudioDomainErrorAdvice(new StudioMessageService());

    LocaleContextHolder.setLocale(Locale.US);
    ResponseEntity<Result<Void>> validation =
        advice.handleValidation(new AiValidationException("agent_provider", "invalid provider"));
    assertEquals(400, validation.getStatusCode().value());
    assertNotNull(validation.getBody());
    Result<Void> validationBody = validation.getBody();
    assertEquals("validation", validationBody.getCode());
    assertEquals("Invalid agent_provider request.", validationBody.getMessage());
    assertEquals(
        Map.of("resource", "agent_provider", "detail", "invalid provider"),
        validationBody.getErrors());

    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    ResponseEntity<Result<Void>> conflict =
        advice.handleVersionConflict(new AiVersionConflictException("agent_model", "3", "4"));
    assertEquals(409, conflict.getStatusCode().value());
    assertNotNull(conflict.getBody());
    Result<Void> conflictBody = conflict.getBody();
    assertEquals("version_conflict", conflictBody.getCode());
    assertEquals("agent_model 已被其他请求修改。", conflictBody.getMessage());
    assertEquals(
        Map.of(
            "resource",
            "agent_model",
            "expectedVersion",
            "3",
            "actualVersion",
            "4",
            "detail",
            "agent_model version conflict: expected=3 actual=4"),
        conflictBody.getErrors());
  }

  @Test
  void localizesMethodArgumentTypeMismatchWithoutLosingTheDiagnosticValue() {
    StudioDomainErrorAdvice advice = new StudioDomainErrorAdvice(new StudioMessageService());
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

    ResponseEntity<Result<Void>> response =
        advice.handleTypeMismatch(
            new MethodArgumentTypeMismatchException("invalid", Integer.class, "limit", null, null));

    assertEquals(400, response.getStatusCode().value());
    assertNotNull(response.getBody());
    Result<Void> body = response.getBody();
    assertEquals("validation", body.getCode());
    assertEquals("limit 参数的值无效。", body.getMessage());
    assertEquals(
        Map.of("resource", "limit", "detail", "limit has invalid value: invalid"),
        body.getErrors());
  }

  @Test
  void localizesEveryDomainKindAndMissingRequestParameters() {
    StudioDomainErrorAdvice advice = new StudioDomainErrorAdvice(new StudioMessageService());
    LocaleContextHolder.setLocale(Locale.US);

    assertError(
        advice.handleNotFound(new AiResourceNotFoundException("agent")),
        404,
        "resource_not_found",
        "The agent was not found.",
        "agent",
        "agent not found");
    assertError(
        advice.handleDuplicate(new AiDuplicateException("model", "duplicate model")),
        409,
        "duplicate",
        "The model already exists.",
        "model",
        "duplicate model");
    assertError(
        advice.handleInUse(new AiInUseException("provider", "provider in use")),
        409,
        "in_use",
        "The provider is still in use.",
        "provider",
        "provider in use");
    assertError(
        advice.handleMissingParam(new MissingServletRequestParameterException("cursor", "String")),
        400,
        "validation",
        "The cursor parameter is required.",
        "cursor",
        "cursor is required");
  }

  private static void assertError(
      ResponseEntity<Result<Void>> response,
      int status,
      String code,
      String message,
      String resource,
      String detail) {
    assertEquals(status, response.getStatusCode().value());
    assertNotNull(response.getBody());
    Result<Void> body = response.getBody();
    assertEquals(code, body.getCode());
    assertEquals(message, body.getMessage());
    assertEquals(Map.of("resource", resource, "detail", detail), body.getErrors());
  }
}
