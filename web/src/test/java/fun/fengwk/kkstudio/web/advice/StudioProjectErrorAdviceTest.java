package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;

import java.util.Locale;
import java.util.Map;

class StudioProjectErrorAdviceTest {

  private final StudioProjectLegacyErrorAdvice advice =
      new StudioProjectLegacyErrorAdvice(new StudioMessageService());
  private final StudioDomainErrorAdvice domain =
      new StudioDomainErrorAdvice(new StudioMessageService());

  @BeforeEach
  void pinEnglishLocale() {
    LocaleContextHolder.setLocale(Locale.US);
  }

  @AfterEach
  void resetLocaleContext() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void handlesVersionConflict() {
    DomainException ex = DomainException.versionConflict("project", "1", "2");
    ResponseEntity<ApiResponse<Void>> response = domain.handle(ex);

    assertEquals(409, response.getStatusCode().value());
    ApiResponse<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("version_conflict", body.getCode());
    assertEquals("The project was modified by another request.", body.getMessage());
    assertEquals(
        Map.of(
            "resource",
            "project",
            "expectedVersion",
            "1",
            "actualVersion",
            "2",
            "detail",
            ex.getMessage()),
        body.getErrors());
  }

  @Test
  void handlesDuplicateConflict() {
    DomainException ex =
        DomainException.duplicate("stage_budget", "Stage budget is already authorized");
    ResponseEntity<ApiResponse<Void>> response = domain.handle(ex);

    assertEquals(409, response.getStatusCode().value());
    ApiResponse<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("duplicate", body.getCode());
    assertEquals(
        Map.of("resource", "stage_budget", "detail", "Stage budget is already authorized"),
        body.getErrors());
  }

  @Test
  void handlesHarnessConflict() {
    HarnessRuntimeConflictException ex =
        new HarnessRuntimeConflictException(
            HarnessRuntimeConflictException.Reason.STALE_VERSION, "issue runtime busy");
    ResponseEntity<ApiResponse<Void>> response = advice.handleHarnessConflict(ex);

    assertEquals(409, response.getStatusCode().value());
    ApiResponse<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_RUNTIME_CONFLICT", body.getCode());
    assertEquals(Map.of("detail", "Issue runtime conflict occurred"), body.getErrors());
  }

  @Test
  void handlesResourceNotFound() {
    DomainException ex = DomainException.notFound("issue");
    ResponseEntity<ApiResponse<Void>> response = domain.handle(ex);

    assertEquals(404, response.getStatusCode().value());
    ApiResponse<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("not_found", body.getCode());
    assertEquals(Map.of("resource", "issue", "detail", ex.getMessage()), body.getErrors());
  }

  @Test
  void handlesHarnessNotFound() {
    HarnessRuntimeNotFoundException ex = new HarnessRuntimeNotFoundException("session missing");
    ResponseEntity<ApiResponse<Void>> response = advice.handleHarnessNotFound(ex);

    assertEquals(404, response.getStatusCode().value());
    ApiResponse<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("PROJECT_RUNTIME_NOT_FOUND", body.getCode());
    assertEquals(Map.of("detail", "Issue runtime resource not found"), body.getErrors());
  }

  @Test
  void handlesValidationAndRuntime() {
    DomainException ex = DomainException.validation("title", "title is required");
    ResponseEntity<ApiResponse<Void>> response = domain.handle(ex);

    assertEquals(400, response.getStatusCode().value());
    ApiResponse<Void> body = response.getBody();
    assertNotNull(body);
    assertEquals("validation", body.getCode());
    assertEquals(Map.of("resource", "title", "detail", "title is required"), body.getErrors());

    ResponseEntity<ApiResponse<Void>> internal =
        advice.handleIllegalState(new IllegalStateException("sensitive internal state"));
    assertEquals(500, internal.getStatusCode().value());
    ApiResponse<Void> internalBody = internal.getBody();
    assertNotNull(internalBody);
    assertEquals("PROJECT_INTERNAL_ERROR", internalBody.getCode());
    assertEquals(Map.of("detail", "Project operation failed"), internalBody.getErrors());

    BindException binding = new BindException(new Object(), "target");
    ResponseEntity<ApiResponse<Void>> bindResponse = advice.handleBinding(binding);
    assertEquals(400, bindResponse.getStatusCode().value());
    ApiResponse<Void> bindBody = bindResponse.getBody();
    assertNotNull(bindBody);
    assertEquals("PROJECT_VALIDATION_ERROR", bindBody.getCode());
  }

  /** 同一条 advice 通道按请求 locale 输出中文本地化文案；errors 仍保留稳定 resource 与 detail。 */
  @Test
  void localizesProjectMessagesForChineseLocale() {
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

    ResponseEntity<ApiResponse<Void>> conflict =
        domain.handle(DomainException.versionConflict("project", "1", "2"));
    assertNotNull(conflict.getBody());
    assertEquals("项目 已被其他请求修改。", conflict.getBody().getMessage());
    assertEquals(
        Map.of(
            "resource",
            "project",
            "expectedVersion",
            "1",
            "actualVersion",
            "2",
            "detail",
            "project version conflict: expected=1 actual=2"),
        conflict.getBody().getErrors());

    ResponseEntity<ApiResponse<Void>> notFound = domain.handle(DomainException.notFound("issue"));
    assertNotNull(notFound.getBody());
    assertEquals("未找到 Issue。", notFound.getBody().getMessage());
    assertEquals(
        Map.of("resource", "issue", "detail", "issue not found"), notFound.getBody().getErrors());

    ResponseEntity<ApiResponse<Void>> internal =
        advice.handleIllegalState(new IllegalStateException("sensitive internal state"));
    assertNotNull(internal.getBody());
    assertEquals("项目操作失败。", internal.getBody().getMessage());
  }
}
