package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.nio.charset.StandardCharsets;

/** 验证 OpenAI Responses 传输异常与协议错误包的确定性分类与所见即所得错误暴露机制。 */
class OpenAiResponsesErrorMapperTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** 验证不同 HTTP 状态码在无响应体时按基线规则正确映射并附带状态码后备信息。 */
  @Test
  void test_httpStatusMapping_withoutBody() {
    ProviderException ex401 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 401, null, null));
    assertNotNull(ex401);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex401.kind());
    assertEquals("HTTP 401: " + OpenAiResponsesErrorMapper.MSG_AUTH, ex401.getMessage());

    ProviderException ex403 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 403, null, null));
    assertNotNull(ex403);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex403.kind());
    assertEquals("HTTP 403: " + OpenAiResponsesErrorMapper.MSG_AUTH, ex403.getMessage());

    ProviderException ex402 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 402, null, null));
    assertNotNull(ex402);
    assertEquals(ProviderErrorKind.BILLING, ex402.kind());
    assertEquals("HTTP 402: " + OpenAiResponsesErrorMapper.MSG_BILLING, ex402.getMessage());

    ProviderException ex400 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 400, null, null));
    assertNotNull(ex400);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex400.kind());
    assertEquals("HTTP 400: " + OpenAiResponsesErrorMapper.MSG_INVALID_REQUEST, ex400.getMessage());

    ProviderException ex429 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 429, null, null));
    assertNotNull(ex429);
    assertEquals(ProviderErrorKind.TRANSIENT, ex429.kind());
    assertEquals("HTTP 429: " + OpenAiResponsesErrorMapper.MSG_TRANSIENT, ex429.getMessage());

    ProviderException ex500 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 500, null, null));
    assertNotNull(ex500);
    assertEquals(ProviderErrorKind.TRANSIENT, ex500.kind());
    assertEquals("HTTP 500: " + OpenAiResponsesErrorMapper.MSG_TRANSIENT, ex500.getMessage());
  }

  /** 验证带有详细 error code 或 type 的响应体正确优先覆盖并映射为细粒度错误类型，且完整保留原始 body。 */
  @Test
  void test_httpStatusMapping_withJsonBody() {
    String overflowJson =
        "{\"error\": {\"type\": \"context_length_exceeded\", \"message\": \"exceeded window\"}}";
    byte[] overflowBody = overflowJson.getBytes(StandardCharsets.UTF_8);
    ProviderException exOverflow =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(
                TransportErrorKind.HTTP_STATUS, "error", 400, overflowBody, null));
    assertNotNull(exOverflow);
    assertEquals(ProviderErrorKind.OVERFLOW, exOverflow.kind());
    assertEquals("HTTP 400\n" + overflowJson, exOverflow.getMessage());

    String billingJson =
        "{\"error\": {\"code\": \"insufficient_quota\", \"message\": \"quota exceeded\"}}";
    byte[] billingBody = billingJson.getBytes(StandardCharsets.UTF_8);
    ProviderException exBilling =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(
                TransportErrorKind.HTTP_STATUS, "error", 400, billingBody, null));
    assertNotNull(exBilling);
    assertEquals(ProviderErrorKind.BILLING, exBilling.kind());
    assertEquals("HTTP 400\n" + billingJson, exBilling.getMessage());
  }

  /** 验证 SSE error 与 failed 事件的 envelope 解析、全字段保留与分类。 */
  @Test
  void test_mapSseErrorEnvelope() throws Exception {
    String json1 =
        "{\"type\": \"response.failed\", \"response\": {\"error\": {\"code\": \"server_error\", \"detail\": \"cluster down\"}}}";
    ProviderException ex1 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json1));
    assertNotNull(ex1);
    assertEquals(ProviderErrorKind.TRANSIENT, ex1.kind());
    assertEquals(OBJECT_MAPPER.readTree(json1).toString(), ex1.getMessage());
    assertTrue(ex1.getMessage().contains("detail"));
    assertTrue(ex1.getMessage().contains("cluster down"));

    String json2 = "{\"type\": \"response.error\", \"error\": {\"code\": \"invalid_api_key\"}}";
    ProviderException ex2 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json2));
    assertNotNull(ex2);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex2.kind());
    assertEquals(OBJECT_MAPPER.readTree(json2).toString(), ex2.getMessage());

    String json3 =
        "{\"type\": \"response.failed\", \"response\": {\"error\": {\"message\": \"tokens exceeded\"}}}";
    ProviderException ex3 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json3));
    assertNotNull(ex3);
    assertEquals(ProviderErrorKind.OVERFLOW, ex3.kind());
    assertEquals(OBJECT_MAPPER.readTree(json3).toString(), ex3.getMessage());

    String json4 =
        "{\"type\": \"response.failed\", \"response\": {\"error\": {\"code\": \"rate_limit_exceeded\"}}}";
    ProviderException ex4 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json4));
    assertNotNull(ex4);
    assertEquals(ProviderErrorKind.TRANSIENT, ex4.kind());
    assertEquals(OBJECT_MAPPER.readTree(json4).toString(), ex4.getMessage());
  }

  /** 验证传输层非致命取消与回调异常返回 null 保持静默。 */
  @Test
  void test_nonFatalTransportExceptions() {
    assertNull(
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.CANCELLED, "cancelled")));
    assertNull(
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.EXECUTOR_REJECTED, "rejected")));
    assertNull(
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.CALLBACK_FAILED, "callback error")));
  }

  /** 验证超时与网络 I/O 错误被归类为 TRANSIENT。 */
  @Test
  void test_networkAndTimeoutExceptions() {
    ProviderException exTimeout =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.TIMEOUT, "timeout"));
    assertEquals(ProviderErrorKind.TRANSIENT, exTimeout.kind());
    assertEquals("OpenAI Responses request timed out", exTimeout.getMessage());

    ProviderException exIo =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.IO, "connection reset"));
    assertEquals(ProviderErrorKind.TRANSIENT, exIo.kind());
    assertEquals("OpenAI Responses I/O error", exIo.getMessage());
  }

  /** 验证错误信息完整暴露远端真实 body，且不向上游泄漏内部 transport message 或底层 cause。 */
  @Test
  void test_errorSanitization() {
    String sensitiveUrl = "https://user:secret@api.openai.com/v1/responses?key=fake-secret123";
    byte[] upstreamBody =
        ("{\"error\": {\"message\": \"failed at "
                + sensitiveUrl
                + "\", \"type\": \"server_error\"}}")
            .getBytes(StandardCharsets.UTF_8);

    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "detailed internal transport error",
            500,
            upstreamBody,
            false,
            null,
            new RuntimeException("secret internal cause"));

    ProviderException pe = OpenAiResponsesErrorMapper.mapTransportException(ex);
    assertNotNull(pe);
    assertEquals(ProviderErrorKind.TRANSIENT, pe.kind());
    assertEquals("HTTP 500\n" + new String(upstreamBody, StandardCharsets.UTF_8), pe.getMessage());
    assertTrue(pe.getMessage().contains("fake-secret123"));
    assertFalse(pe.getMessage().contains("detailed internal transport error"));
    assertNull(pe.getCause());
  }

  /** 验证各 HTTP 状态码与异常类型边缘分支覆盖。 */
  @Test
  void test_statusAndEnvelopeEdgeCases() throws Exception {
    assertEquals(
        ProviderErrorKind.TRANSIENT, OpenAiResponsesErrorMapper.mapTransportException(null).kind());

    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(TransportErrorKind.INVALID_RESPONSE, "bad resp"))
            .kind());

    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(null).kind());
    assertEquals(
        OpenAiResponsesErrorMapper.MSG_INVALID_RESPONSE,
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(null).getMessage());

    // 非对象 envelope 保留原始字符串并归类为 INVALID_RESPONSE
    ProviderException exArray =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree("[\"not_object\"]"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exArray.kind());
    assertEquals("[\"not_object\"]", exArray.getMessage());

    assertEquals(
        ProviderErrorKind.BILLING,
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(
                OBJECT_MAPPER.readTree("{\"type\":\"quota_exceeded\"}"))
            .kind());

    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(
                OBJECT_MAPPER.readTree("{\"code\":\"invalid_request_error\"}"))
            .kind());

    // 402 billing
    assertEquals(
        ProviderErrorKind.BILLING,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(TransportErrorKind.HTTP_STATUS, "err", 402, null, null))
            .kind());

    // 413 invalid request
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(TransportErrorKind.HTTP_STATUS, "err", 413, null, null))
            .kind());

    // 422 invalid request
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(TransportErrorKind.HTTP_STATUS, "err", 422, null, null))
            .kind());

    // 404 invalid request
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(TransportErrorKind.HTTP_STATUS, "err", 404, null, null))
            .kind());

    // 503 transient
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(TransportErrorKind.HTTP_STATUS, "err", 503, null, null))
            .kind());

    // body with code or type directly
    byte[] directCodeBody = "{\"code\": \"billing_not_active\"}".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        ProviderErrorKind.BILLING,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(
                    TransportErrorKind.HTTP_STATUS, "err", 400, directCodeBody, null))
            .kind());

    byte[] directTypeBody = "{\"type\": \"tokens exceeded\"}".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        ProviderErrorKind.OVERFLOW,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(
                    TransportErrorKind.HTTP_STATUS, "err", 400, directTypeBody, null))
            .kind());

    byte[] errorTypeBody =
        "{\"error\": {\"type\": \"tokens exceeded\"}}".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        ProviderErrorKind.OVERFLOW,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(
                    TransportErrorKind.HTTP_STATUS, "err", 400, errorTypeBody, null))
            .kind());

    byte[] malformedBody = "{invalid-json".getBytes(StandardCharsets.UTF_8);
    ProviderException peMalformed =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(
                TransportErrorKind.HTTP_STATUS, "err", 400, malformedBody, null));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, peMalformed.kind());
    assertEquals("HTTP 400\n{invalid-json", peMalformed.getMessage());

    // 状态码 600
    ProviderException pe600 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "err", 600, null, null));
    assertEquals(ProviderErrorKind.TRANSIENT, pe600.kind());
    assertEquals("HTTP 600: " + OpenAiResponsesErrorMapper.MSG_TRANSIENT, pe600.getMessage());

    // 状态码 200 出现在异常中
    ProviderException pe200 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "err", 200, null, null));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, pe200.kind());
  }

  @Test
  @DisplayName("完整保留空白、非 JSON、截断标记与多行 Unicode 正文")
  void test_exactBodyWhitespaceAndTruncation() {
    String whitespaceBody = "\n\t  {\"error\":\"something\"}  \n";
    TransportException exWhitespace =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "err",
            400,
            whitespaceBody.getBytes(StandardCharsets.UTF_8),
            null);
    ProviderException peWhitespace = OpenAiResponsesErrorMapper.mapTransportException(exWhitespace);
    assertEquals("HTTP 400\n" + whitespaceBody, peWhitespace.getMessage());

    String unicodeBody = "系统错误：服务过载\nDetail: 详细原因 \uD83D\uDD25\nTrace: abc-123";
    TransportException exUnicode =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "err",
            503,
            unicodeBody.getBytes(StandardCharsets.UTF_8),
            null);
    ProviderException peUnicode = OpenAiResponsesErrorMapper.mapTransportException(exUnicode);
    assertEquals(ProviderErrorKind.TRANSIENT, peUnicode.kind());
    assertEquals("HTTP 503\n" + unicodeBody, peUnicode.getMessage());

    byte[] truncatedBytes = "partial responses error...".getBytes(StandardCharsets.UTF_8);
    TransportException exTruncated =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "err", 500, truncatedBytes, true, null, null);
    ProviderException peTruncated = OpenAiResponsesErrorMapper.mapTransportException(exTruncated);
    assertEquals(
        "HTTP 500\npartial responses error..." + ProviderErrorHelper.TRUNCATION_MARKER,
        peTruncated.getMessage());
  }
}
