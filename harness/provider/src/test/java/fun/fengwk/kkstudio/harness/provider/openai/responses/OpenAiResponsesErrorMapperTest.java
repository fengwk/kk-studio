package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.nio.charset.StandardCharsets;

/** 验证 OpenAI Responses 传输异常与协议错误包的确定性分类与严格脱敏保护。 */
class OpenAiResponsesErrorMapperTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** 验证不同 HTTP 状态码在无响应体时按基线规则正确映射。 */
  @Test
  void test_httpStatusMapping_withoutBody() {
    ProviderException ex401 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 401, null, null));
    assertNotNull(ex401);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex401.kind());

    ProviderException ex403 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 403, null, null));
    assertNotNull(ex403);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex403.kind());

    ProviderException ex402 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 402, null, null));
    assertNotNull(ex402);
    assertEquals(ProviderErrorKind.BILLING, ex402.kind());

    ProviderException ex400 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 400, null, null));
    assertNotNull(ex400);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex400.kind());

    ProviderException ex429 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 429, null, null));
    assertNotNull(ex429);
    assertEquals(ProviderErrorKind.TRANSIENT, ex429.kind());

    ProviderException ex500 =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.HTTP_STATUS, "error", 500, null, null));
    assertNotNull(ex500);
    assertEquals(ProviderErrorKind.TRANSIENT, ex500.kind());
  }

  /** 验证带有详细 error code 或 type 的响应体正确优先覆盖并映射为细粒度错误类型。 */
  @Test
  void test_httpStatusMapping_withJsonBody() {
    byte[] overflowBody =
        "{\"error\": {\"type\": \"context_length_exceeded\", \"message\": \"exceeded window\"}}"
            .getBytes(StandardCharsets.UTF_8);
    ProviderException exOverflow =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(
                TransportErrorKind.HTTP_STATUS, "error", 400, overflowBody, null));
    assertNotNull(exOverflow);
    assertEquals(ProviderErrorKind.OVERFLOW, exOverflow.kind());

    byte[] billingBody =
        "{\"error\": {\"code\": \"insufficient_quota\", \"message\": \"quota exceeded\"}}"
            .getBytes(StandardCharsets.UTF_8);
    ProviderException exBilling =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(
                TransportErrorKind.HTTP_STATUS, "error", 400, billingBody, null));
    assertNotNull(exBilling);
    assertEquals(ProviderErrorKind.BILLING, exBilling.kind());
  }

  /** 验证 SSE error 与 failed 事件的 envelope 解析与分类。 */
  @Test
  void test_mapSseErrorEnvelope() throws Exception {
    String json1 =
        "{\"type\": \"response.failed\", \"response\": {\"error\": {\"code\": \"server_error\"}}}";
    ProviderException ex1 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json1));
    assertNotNull(ex1);
    assertEquals(ProviderErrorKind.TRANSIENT, ex1.kind());

    String json2 = "{\"type\": \"response.error\", \"error\": {\"code\": \"invalid_api_key\"}}";
    ProviderException ex2 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json2));
    assertNotNull(ex2);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex2.kind());

    String json3 =
        "{\"type\": \"response.failed\", \"response\": {\"error\": {\"message\": \"tokens exceeded\"}}}";
    ProviderException ex3 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json3));
    assertNotNull(ex3);
    assertEquals(ProviderErrorKind.OVERFLOW, ex3.kind());

    String json4 =
        "{\"type\": \"response.failed\", \"response\": {\"error\": {\"code\": \"rate_limit_exceeded\"}}}";
    ProviderException ex4 =
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree(json4));
    assertNotNull(ex4);
    assertEquals(ProviderErrorKind.TRANSIENT, ex4.kind());
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

    ProviderException exIo =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.IO, "connection reset"));
    assertEquals(ProviderErrorKind.TRANSIENT, exIo.kind());
  }

  /** 验证错误信息完全脱敏，不泄漏原始响应 body、URL、敏感 API Key 或底层敏感堆栈。 */
  @Test
  void test_errorSanitization() {
    String sensitiveUrl = "https://user:secret@api.openai.com/v1/responses?key=sk-secret123";
    byte[] sensitiveBody =
        ("{\"error\": {\"message\": \"failed at "
                + sensitiveUrl
                + "\", \"type\": \"server_error\"}}")
            .getBytes(StandardCharsets.UTF_8);

    ProviderException ex =
        OpenAiResponsesErrorMapper.mapTransportException(
            new TransportException(
                TransportErrorKind.HTTP_STATUS, "detailed err", 500, sensitiveBody, null));
    assertNotNull(ex);
    assertFalse(ex.getMessage().contains("secret"));
    assertFalse(ex.getMessage().contains("sk-secret123"));
    assertFalse(ex.getMessage().contains("https://"));
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
        ProviderErrorKind.INVALID_RESPONSE,
        OpenAiResponsesErrorMapper.mapSseErrorEnvelope(OBJECT_MAPPER.readTree("[\"not_object\"]"))
            .kind());

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
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiResponsesErrorMapper.mapTransportException(
                new TransportException(
                    TransportErrorKind.HTTP_STATUS, "err", 400, malformedBody, null))
            .kind());
  }
}
