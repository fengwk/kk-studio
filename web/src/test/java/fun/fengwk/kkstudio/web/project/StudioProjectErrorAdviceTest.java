package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fun.fengwk.convention4j.api.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;

import java.util.Map;

class StudioProjectErrorAdviceTest {

  private final StudioProjectErrorAdvice advice = new StudioProjectErrorAdvice();

  @Test
  void scopesAdviceToSupportedControllers() {
    RestControllerAdvice ann =
        StudioProjectErrorAdvice.class.getAnnotation(RestControllerAdvice.class);
    assertArrayEquals(
        new Class<?>[] {StudioProjectController.class, StudioIssueController.class},
        ann.assignableTypes());
  }

  @Test
  void handlesVersionConflict() {
    ProjectVersionConflictException ex = new ProjectVersionConflictException("project", "1", "2");
    ResponseEntity<Result<Void>> response = advice.handleVersionConflict(ex);

    assertEquals(409, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_VERSION_CONFLICT", body.getCode());
    assertEquals("Version conflict for project", body.getMessage());
    assertEquals(
        Map.of("resource", "project", "expectedVersion", "1", "actualVersion", "2"),
        body.getErrors());
  }

  @Test
  void handlesDuplicateConflict() {
    ProjectDuplicateException ex =
        new ProjectDuplicateException("stage_budget", "Stage budget is already authorized");
    ResponseEntity<Result<Void>> response = advice.handleDuplicate(ex);

    assertEquals(409, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_DUPLICATE_CONFLICT", body.getCode());
    assertEquals(
        Map.of("resource", "stage_budget", "detail", "Stage budget is already authorized"),
        body.getErrors());
  }

  @Test
  void handlesHarnessConflict() {
    HarnessRuntimeConflictException ex =
        new HarnessRuntimeConflictException(
            HarnessRuntimeConflictException.Reason.STALE_VERSION, "issue runtime busy");
    ResponseEntity<Result<Void>> response = advice.handleHarnessConflict(ex);

    assertEquals(409, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_RUNTIME_CONFLICT", body.getCode());
    assertEquals(Map.of("detail", "Issue runtime conflict occurred"), body.getErrors());
  }

  @Test
  void handlesResourceNotFound() {
    ProjectNotFoundException ex = new ProjectNotFoundException("issue");
    ResponseEntity<Result<Void>> response = advice.handleResourceNotFound(ex);

    assertEquals(404, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_RESOURCE_NOT_FOUND", body.getCode());
    assertEquals(Map.of("resource", "issue"), body.getErrors());
  }

  @Test
  void handlesHarnessNotFound() {
    HarnessRuntimeNotFoundException ex = new HarnessRuntimeNotFoundException("session missing");
    ResponseEntity<Result<Void>> response = advice.handleHarnessNotFound(ex);

    assertEquals(404, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_RUNTIME_NOT_FOUND", body.getCode());
    assertEquals(Map.of("detail", "Issue runtime resource not found"), body.getErrors());
  }

  @Test
  void handlesValidationAndRuntime() {
    ProjectValidationException ex = new ProjectValidationException("title", "title is required");
    ResponseEntity<Result<Void>> response = advice.handleValidation(ex);

    assertEquals(400, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_VALIDATION_ERROR", body.getCode());
    assertEquals(Map.of("detail", "title is required"), body.getErrors());

    ResponseEntity<Result<Void>> internal =
        advice.handleIllegalState(new IllegalStateException("sensitive internal state"));
    assertEquals(500, internal.getStatusCode().value());
    Result<Void> internalBody = internal.getBody();
    assertNotNull(internalBody);
    assertEquals("PROJECT_INTERNAL_ERROR", internalBody.getCode());
    assertEquals(Map.of("detail", "Project operation failed"), internalBody.getErrors());

    BindException binding = new BindException(new Object(), "target");
    ResponseEntity<Result<Void>> bindResponse = advice.handleBinding(binding);
    assertEquals(400, bindResponse.getStatusCode().value());
    Result<Void> bindBody = bindResponse.getBody();
    assertNotNull(bindBody);
    assertEquals("PROJECT_VALIDATION_ERROR", bindBody.getCode());
  }
}
