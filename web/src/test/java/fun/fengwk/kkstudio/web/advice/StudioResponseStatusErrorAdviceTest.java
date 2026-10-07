package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fun.fengwk.convention4j.api.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException.Reason;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessRequestFormatException;
import fun.fengwk.kkstudio.web.controller.StudioCanvasController;
import fun.fengwk.kkstudio.web.controller.StudioCanvasFunctionController;
import fun.fengwk.kkstudio.web.controller.StudioCanvasResourceController;
import fun.fengwk.kkstudio.web.controller.StudioChatController;
import fun.fengwk.kkstudio.web.controller.StudioConfigSyncController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessCommandBatchController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessProviderRequestPreviewController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessSessionController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessSessionProviderRequestPreviewController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessThreadCommandBatchController;
import fun.fengwk.kkstudio.web.controller.StudioHarnessThreadController;
import fun.fengwk.kkstudio.web.controller.StudioInteractionController;
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
  void scopesAdviceToSupportedControllersIncludingSync() {
    // 同步请求反序列化错误沿用安全翻译，其他控制器仍维持原有边界。
    RestControllerAdvice annotation =
        StudioResponseStatusErrorAdvice.class.getAnnotation(RestControllerAdvice.class);
    assertArrayEquals(
        new Class<?>[] {
          StudioCanvasController.class,
          StudioCanvasFunctionController.class,
          StudioCanvasResourceController.class,
          StudioChatController.class,
          StudioHarnessCommandBatchController.class,
          StudioHarnessProviderRequestPreviewController.class,
          StudioHarnessSessionController.class,
          StudioHarnessSessionProviderRequestPreviewController.class,
          StudioHarnessThreadCommandBatchController.class,
          StudioHarnessThreadController.class,
          StudioInteractionController.class,
          StudioConfigSyncController.class
        },
        annotation.assignableTypes());
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

  @Test
  void exposesEveryPreviewReasonWithoutLeakingCauseDetails() {
    // 测试意图：九个 wire name 逐一保留，内部原因链不进入 errors；locale 不改变机器码。
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    for (Reason reason : Reason.values()) {
      var preview =
          new ProviderRequestPreviewUnavailableException(
              reason,
              "safe preview detail",
              new IllegalStateException("private connection detail"));
      var response =
          advice.handle(
              new ResponseStatusException(HttpStatus.CONFLICT, preview.getMessage(), preview),
              null);
      assertEquals(409, response.getStatusCode().value());
      assertEquals(
          Map.of(
              "type",
              "about:blank",
              "title",
              "冲突",
              "detail",
              "safe preview detail",
              "reason",
              reason.name()),
          response.getBody().getErrors());
    }
  }

  @Test
  void doesNotInferPreviewReasonFromTextOrNestedCause() {
    // 测试意图：只识别直接 typed cause，不以英文描述匹配，也不遍历内部原因链。
    var preview =
        new ProviderRequestPreviewUnavailableException(Reason.PREVIEW_STALE_CURSOR, "safe detail");
    for (Throwable cause : new Throwable[] {null, new RuntimeException("wrapper", preview)}) {
      var response =
          advice.handle(
              new ResponseStatusException(
                  HttpStatus.CONFLICT, "thread head/next command sequence", cause),
              null);
      assertEquals(
          Map.of(
              "type",
              "about:blank",
              "title",
              "Conflict",
              "detail",
              "thread head/next command sequence"),
          response.getBody().getErrors());
    }
  }

  @Test
  void unwrapsHarnessRequestFormatExceptionMessageForHttpMessageNotReadable() {
    // 意图：反序列化异常 Cause chain 中若包含 HarnessRequestFormatException，将其 message 提取为 400 detail。
    HttpMessageNotReadableException error =
        new HttpMessageNotReadableException(
            "JSON parse error: wrapping detail",
            new RuntimeException(
                "jackson wrapping",
                new HarnessRequestFormatException("unknown HTTP command field: environment")),
            new MockHttpInputMessage(new byte[0]));

    ResponseEntity<Result<Void>> response = advice.handle(error, new MockHttpServletRequest());

    assertEquals(400, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertEquals("BAD_REQUEST", body.getCode());
    assertEquals("The request is invalid.", body.getMessage());
    assertEquals(
        Map.of(
            "type", "about:blank",
            "title", "Bad Request",
            "detail", "unknown HTTP command field: environment"),
        body.getErrors());
  }

  @Test
  void fallsBackToGenericDetailForOrdinaryIllegalArgumentException() {
    // 意图：普通 IllegalArgumentException 绝不向外暴露内部敏感信息，安全回退通用 detail。
    HttpMessageNotReadableException error =
        new HttpMessageNotReadableException(
            "JSON parse error",
            new IllegalArgumentException("sensitive internal detail"),
            new MockHttpInputMessage(new byte[0]));

    ResponseEntity<Result<Void>> response = advice.handle(error, new MockHttpServletRequest());

    assertEquals(400, response.getStatusCode().value());
    assertEquals("Failed to read request", response.getBody().getErrors().get("detail"));
  }

  @Test
  void fallsBackToGenericDetailWhenNoHarnessRequestFormatExceptionPresent() {
    // 意图：原生 Jackson 解析语法错误安全回退到通用 detail，不泄露底层 parser/source 文本。
    HttpMessageNotReadableException error =
        new HttpMessageNotReadableException(
            "JSON parse error: Unexpected character ('x': code 120) in JSON",
            new RuntimeException("malformed token"),
            new MockHttpInputMessage(new byte[0]));

    ResponseEntity<Result<Void>> response = advice.handle(error, new MockHttpServletRequest());

    assertEquals(400, response.getStatusCode().value());
    Result<Void> body = response.getBody();
    assertEquals("BAD_REQUEST", body.getCode());
    assertEquals("The request is invalid.", body.getMessage());
    assertEquals(
        Map.of(
            "type", "about:blank",
            "title", "Bad Request",
            "detail", "Failed to read request"),
        body.getErrors());
  }

  @Test
  void fallsBackToGenericDetailWhenHarnessRequestFormatExceptionMessageIsBlank() {
    // 意图：即使存在专用格式异常，若 message 为空白仍安全回退通用 detail。
    HttpMessageNotReadableException error =
        new HttpMessageNotReadableException(
            "JSON parse error",
            new HarnessRequestFormatException("   "),
            new MockHttpInputMessage(new byte[0]));

    ResponseEntity<Result<Void>> response = advice.handle(error, new MockHttpServletRequest());

    assertEquals(400, response.getStatusCode().value());
    assertEquals("Failed to read request", response.getBody().getErrors().get("detail"));
  }
}
