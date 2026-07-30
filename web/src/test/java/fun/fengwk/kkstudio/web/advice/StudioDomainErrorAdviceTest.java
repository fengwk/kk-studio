package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fun.fengwk.convention4j.api.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.web.controller.StudioAgentDefinitionController;
import fun.fengwk.kkstudio.web.controller.StudioAgentModelController;
import fun.fengwk.kkstudio.web.controller.StudioAgentProviderController;
import fun.fengwk.kkstudio.web.controller.StudioChatController;

import java.util.Map;

class StudioDomainErrorAdviceTest {

  @Test
  void scopesAdviceToCatalogControllersOnly() {
    RestControllerAdvice advice =
        StudioDomainErrorAdvice.class.getAnnotation(RestControllerAdvice.class);

    assertArrayEquals(
        new Class<?>[] {
          StudioAgentProviderController.class,
          StudioAgentModelController.class,
          StudioAgentDefinitionController.class,
          StudioChatController.class
        },
        advice.assignableTypes());
  }

  @Test
  void returnsTypedCodeAndUsefulErrorContext() {
    StudioDomainErrorAdvice advice = new StudioDomainErrorAdvice();

    ResponseEntity<Result<Void>> validation =
        advice.handleValidation(new AiValidationException("agent_provider", "invalid provider"));
    assertEquals(400, validation.getStatusCode().value());
    assertEquals("validation", validation.getBody().getCode());
    assertEquals("agent_provider", validation.getBody().getErrors().get("resource"));

    ResponseEntity<Result<Void>> conflict =
        advice.handleVersionConflict(new AiVersionConflictException("agent_model", "2", "3", "4"));
    assertEquals(409, conflict.getStatusCode().value());
    assertEquals("version_conflict", conflict.getBody().getCode());
    assertEquals(
        Map.of("resource", "agent_model", "expectedVersion", "3", "actualVersion", "4"),
        conflict.getBody().getErrors());
  }
}
