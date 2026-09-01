package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fun.fengwk.convention4j.api.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.Locale;
import java.util.Map;

class StudioResponseStatusErrorAdviceTest {

  private final StudioResponseStatusErrorAdvice advice =
      new StudioResponseStatusErrorAdvice(new StudioMessageService());

  private Locale originalDefault;

  @BeforeEach
  void pinDefaultLocaleToEnglish() {
    // 回退契约（缺失/不支持 locale -> 英文）依赖 JVM default locale；显式锚定为 US，
    // 避免宿主环境（如 zh_CN）使回退语义不确定。
    originalDefault = Locale.getDefault();
    Locale.setDefault(Locale.US);
  }

  @AfterEach
  void resetLocaleContext() {
    LocaleContextHolder.resetLocaleContext();
    Locale.setDefault(originalDefault);
  }

  @Test
  void translatesResponseStatusExceptionWithoutChangingProblemContext() {
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    ResponseStatusException error =
        new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown canvas: 42");

    ResponseEntity<Result<Void>> response = advice.handle(error, new MockHttpServletRequest());

    assertEquals(404, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertEquals("NOT_FOUND", body.getCode());
    assertEquals("未找到请求的资源。", body.getMessage());
    assertEquals(
        Map.of(
            "type", "about:blank",
            "title", "未找到",
            "detail", "unknown canvas: 42"),
        body.getErrors());
  }

  @Test
  void fallsBackToNumericCodeAndEnglishTextForUnknownStatus() {
    LocaleContextHolder.setLocale(Locale.FRANCE);
    ResponseStatusException error =
        new ResponseStatusException(HttpStatusCode.valueOf(599), "upstream detail");

    ResponseEntity<Result<Void>> response = advice.handle(error, null);

    assertEquals(599, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertEquals("599", body.getCode());
    assertEquals("HTTP error (status 599).", body.getMessage());
    assertEquals(
        Map.of("type", "about:blank", "title", "HTTP Error", "detail", "upstream detail"),
        body.getErrors());
  }

  @Test
  void exposesTheStableHarnessConflictReasonForClientRecovery() {
    HarnessRuntimeConflictException conflict =
        new HarnessRuntimeConflictException(
            HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "stale cursor");
    ResponseStatusException error =
        new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);

    ResponseEntity<Result<Void>> response = advice.handle(error, new MockHttpServletRequest());

    assertEquals(409, response.getStatusCode().value());
    assertEquals(
        Map.of(
            "type",
            "about:blank",
            "title",
            "Conflict",
            "detail",
            "stale cursor",
            "reason",
            "STALE_COMMAND_CURSOR"),
        response.getBody().getErrors());
  }
}
