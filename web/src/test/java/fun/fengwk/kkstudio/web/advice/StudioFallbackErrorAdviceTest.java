package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import fun.fengwk.kkstudio.web.api.ApiResponse;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.Locale;

class StudioFallbackErrorAdviceTest {

  private final StudioFallbackErrorAdvice advice =
      new StudioFallbackErrorAdvice(new StudioMessageService());

  @BeforeEach
  void setUp() {
    LocaleContextHolder.setLocale(Locale.US);
  }

  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void responseStatusExceptionUsesItsStatusAndSafeFallbackCopy() {
    ResponseEntity<ApiResponse<Void>> response =
        advice.handle(new ResponseStatusException(HttpStatusCode.valueOf(418), "teapot"));

    ApiResponse<Void> body = response.getBody();
    assertEquals(418, response.getStatusCode().value());
    assertEquals(418, body.getStatus());
    assertEquals("I_AM_A_TEAPOT", body.getCode());
    assertEquals("HTTP error (status 418).", body.getMessage());
    assertEquals("HTTP Error", body.getErrors().get("title"));
    assertEquals("teapot", body.getErrors().get("detail"));
  }

  @Test
  void unreadableBodyUsesFixedDetailWithoutParserMessage() {
    ResponseEntity<ApiResponse<Void>> response =
        advice.handle(
            new HttpMessageNotReadableException("parser-detail", (HttpInputMessage) null));

    ApiResponse<Void> body = response.getBody();
    assertEquals(400, response.getStatusCode().value());
    assertEquals("Failed to read request", body.getErrors().get("detail"));
    assertFalse(String.valueOf(body.getErrors().get("detail")).contains("parser-detail"));
  }

  @Test
  void springErrorResponseKeepsItsHttpStatus() {
    ResponseEntity<ApiResponse<Void>> response =
        advice.handleUnexpected(
            new NoResourceFoundException(HttpMethod.GET, "/missing.txt", "missing.txt"));

    ApiResponse<Void> body = response.getBody();
    assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
    assertEquals("NOT_FOUND", body.getCode());
    assertTrue(String.valueOf(body.getErrors().get("detail")).contains("missing.txt"));
  }

  @Test
  void unexpectedExceptionReturnsSanitizedInternalServerError() {
    ResponseEntity<ApiResponse<Void>> response =
        advice.handleUnexpected(new IllegalStateException("secret internal detail"));

    ApiResponse<Void> body = response.getBody();
    assertEquals(500, response.getStatusCode().value());
    assertEquals("INTERNAL_SERVER_ERROR", body.getCode());
    assertEquals("The server encountered an unexpected error.", body.getMessage());
    assertFalse(body.getErrors().containsKey("detail"));
    assertFalse(body.getMessage().contains("secret internal detail"));
  }

  @Test
  void internalServerErrorIsLocalizedForChineseRequests() {
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

    ApiResponse<Void> body = advice.handleUnexpected(new IllegalStateException()).getBody();
    assertEquals("服务器发生意外错误。", body.getMessage());
    assertEquals("服务器错误", body.getErrors().get("title"));
  }
}
