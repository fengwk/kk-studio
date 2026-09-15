package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.nio.charset.StandardCharsets;

/** 测试意图：验证 OpenAI Chat 异常映射与脱敏机制，确保正确归类且绝不泄露敏感数据。 */
class OpenAiChatErrorMapperTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("传输层可取消/被拒绝/回调失败时静默返回 null")
  void silentOnCancelledOrRejected() {
    assertNull(
        OpenAiChatErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.CANCELLED, "cancelled")));
    assertNull(
        OpenAiChatErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.EXECUTOR_REJECTED, "rejected")));
    assertNull(
        OpenAiChatErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.CALLBACK_FAILED, "callback failed")));
  }

  @Test
  @DisplayName("超时与 IO 异常映射为 TRANSIENT")
  void mapTimeoutAndIo() {
    ProviderException ex1 =
        OpenAiChatErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.TIMEOUT, "timeout"));
    assertNotNull(ex1);
    assertEquals(ProviderErrorKind.TRANSIENT, ex1.kind());

    ProviderException ex2 =
        OpenAiChatErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.IO, "io error"));
    assertNotNull(ex2);
    assertEquals(ProviderErrorKind.TRANSIENT, ex2.kind());
  }

  @Test
  @DisplayName("HTTP 状态码与 error payload 映射分类")
  void mapHttpStatusAndTypes() {
    // 401 认证失败
    TransportException ex401 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            401,
            "{\"error\":{\"code\":\"invalid_api_key\"}}".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe401 = OpenAiChatErrorMapper.mapTransportException(ex401);
    assertEquals(ProviderErrorKind.AUTHENTICATION, pe401.kind());

    // 402 / insufficient_quota 配额不足
    TransportException ex402 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            429,
            "{\"error\":{\"type\":\"insufficient_quota\"}}".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe402 = OpenAiChatErrorMapper.mapTransportException(ex402);
    assertEquals(ProviderErrorKind.BILLING, pe402.kind());

    // context_length_exceeded
    TransportException exOverflow =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            "{\"error\":{\"code\":\"context_length_exceeded\"}}".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peOverflow = OpenAiChatErrorMapper.mapTransportException(exOverflow);
    assertEquals(ProviderErrorKind.OVERFLOW, peOverflow.kind());

    // 413
    TransportException ex413 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "status", 413, new byte[0], null, null);
    ProviderException pe413 = OpenAiChatErrorMapper.mapTransportException(ex413);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, pe413.kind());

    // 429 rate limit
    TransportException ex429 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "status", 429, new byte[0], null, null);
    ProviderException pe429 = OpenAiChatErrorMapper.mapTransportException(ex429);
    assertEquals(ProviderErrorKind.TRANSIENT, pe429.kind());

    // 500 server error
    TransportException ex500 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "status", 500, new byte[0], null, null);
    ProviderException pe500 = OpenAiChatErrorMapper.mapTransportException(ex500);
    assertEquals(ProviderErrorKind.TRANSIENT, pe500.kind());

    // 显式 request_too_large 保持优先于服务端状态码
    TransportException ex500TooLarge =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            500,
            "{\"error\":{\"code\":\"request_too_large\"}}".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiChatErrorMapper.mapTransportException(ex500TooLarge).kind());
  }

  @Test
  @DisplayName("SSE error envelope 节点脱敏解析")
  void mapSseErrorEnvelope() throws Exception {
    String json = "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}";
    ProviderException pe = OpenAiChatErrorMapper.mapSseErrorEnvelope(MAPPER.readTree(json));
    assertEquals(ProviderErrorKind.TRANSIENT, pe.kind());
    assertFalse(pe.getMessage().contains("Rate limit reached")); // 消息应脱敏

    // 顶级 type
    String json2 = "{\"type\":\"insufficient_quota\"}";
    ProviderException pe2 = OpenAiChatErrorMapper.mapSseErrorEnvelope(MAPPER.readTree(json2));
    assertEquals(ProviderErrorKind.BILLING, pe2.kind());

    // 错误节点为空或非对象
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, OpenAiChatErrorMapper.mapSseErrorEnvelope(null).kind());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        OpenAiChatErrorMapper.mapSseErrorEnvelope(MAPPER.readTree("[]")).kind());
  }

  @Test
  @DisplayName("异常映射完整边界与私有构造器")
  void testErrorMapperCompleteBoundaries() throws Exception {
    // null 异常
    assertEquals(
        ProviderErrorKind.TRANSIENT, OpenAiChatErrorMapper.mapTransportException(null).kind());

    // INVALID_RESPONSE 传输异常
    TransportException exInv =
        new TransportException(TransportErrorKind.INVALID_RESPONSE, "invalid response");
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        OpenAiChatErrorMapper.mapTransportException(exInv).kind());

    // 损坏的 errorBodyBytes 降级状态码处理
    TransportException exCorrupted =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            "{not-json".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiChatErrorMapper.mapTransportException(exCorrupted).kind());

    // 非对象的 JSON 数组降级状态码处理
    TransportException exArrayJson =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            "[\"item\"]".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiChatErrorMapper.mapTransportException(exArrayJson).kind());

    // 403 Forbidden
    TransportException ex403 =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 403, null, null, null);
    assertEquals(
        ProviderErrorKind.AUTHENTICATION,
        OpenAiChatErrorMapper.mapTransportException(ex403).kind());

    // 402 Billing
    TransportException ex402Direct =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 402, null, null, null);
    assertEquals(
        ProviderErrorKind.BILLING, OpenAiChatErrorMapper.mapTransportException(ex402Direct).kind());

    // 404 Invalid Request
    TransportException ex404 =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 404, null, null, null);
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        OpenAiChatErrorMapper.mapTransportException(ex404).kind());

    // 各种业务细分 errorType
    TransportException exCredit =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            "{\"error\":{\"type\":\"insufficient_credit\"}}".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        ProviderErrorKind.BILLING, OpenAiChatErrorMapper.mapTransportException(exCredit).kind());

    TransportException exWindow =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            "{\"error\":{\"code\":\"model_context_window_exceeded\"}}"
                .getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        ProviderErrorKind.OVERFLOW, OpenAiChatErrorMapper.mapTransportException(exWindow).kind());

    TransportException exOverloaded =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            503,
            "{\"error\":{\"type\":\"server_overloaded\"}}".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        OpenAiChatErrorMapper.mapTransportException(exOverloaded).kind());

    // 顶层 type 且非 4xx/5xx 返回 INVALID_RESPONSE
    TransportException exTopType =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            200,
            "{\"type\":\"custom_unknown\"}".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        OpenAiChatErrorMapper.mapTransportException(exTopType).kind());

    // 状态码 600（>=500 但非 500-599）返回 TRANSIENT
    TransportException ex600 =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 600, null, null, null);
    assertEquals(
        ProviderErrorKind.TRANSIENT, OpenAiChatErrorMapper.mapTransportException(ex600).kind());

    // 私有构造器反射
    var ctor = OpenAiChatErrorMapper.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertNotNull(ctor.newInstance());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          {"error":{"type":"invalid_request_error","code":"invalid_value","param":"max_tokens"}} | OpenAI invalid request (type=invalid_request_error, code=invalid_value, param=max_tokens)
          {"error":{"param":"max_tokens"}}                                                        | OpenAI invalid request (param=max_tokens)
          {"error":{"type":"invalid_request_error","param":"messages[0].content"}}                 | OpenAI invalid request (type=invalid_request_error, param=messages)
          {"error":{"param":"/tools/0/function/arguments"}}                                       | OpenAI invalid request (param=tools)
          """)
  void testSafeMetadataFormatting(String json, String expected) {
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            json.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe = OpenAiChatErrorMapper.mapTransportException(ex);
    assertNotNull(pe);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, pe.kind());
    assertEquals(expected, pe.getMessage());
  }

  @Test
  void testTypeAndCodeOverflowClassification() {
    String json =
        "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}";
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            json.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe = OpenAiChatErrorMapper.mapTransportException(ex);
    assertNotNull(pe);
    assertEquals(ProviderErrorKind.OVERFLOW, pe.kind());
    assertEquals(OpenAiChatErrorMapper.MSG_OVERFLOW, pe.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"error\":{\"param\":\"sk-proj-secret-12345\"}}",
        "{\"error\":{\"param\":\"bearer_secret_token\"}}",
        "{\"error\":{\"param\":\"https://api.openai.com/v1/chat\"}}",
        "{\"error\":{\"param\":\"max tokens\"}}",
        "{\"error\":{\"param\":\"randomUpstreamToken12345\"}}",
        "{\"error\":{\"type\":\"provider_supplied_identifier\"}}",
        "{\"error\":{\"param\":12345}}",
        "{\"error\":{\"param\":true}}"
      })
  void testUnsafeAndNonTextMetadataOmission(String json) {
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            json.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe = OpenAiChatErrorMapper.mapTransportException(ex);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, pe.kind());
    assertEquals(OpenAiChatErrorMapper.MSG_INVALID_REQUEST, pe.getMessage());
  }

  @Test
  void testOversizedMetadataOmission() {
    String oversized = "a".repeat(129);
    String json =
        "{\"error\":{\"type\":\""
            + oversized
            + "\",\"code\":\""
            + oversized
            + "\",\"param\":\""
            + oversized
            + "\"}}";
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            json.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        OpenAiChatErrorMapper.MSG_INVALID_REQUEST,
        OpenAiChatErrorMapper.mapTransportException(ex).getMessage());
  }

  @Test
  void testRawMessageAndCredentialNonLeakage() {
    String json =
        "{\"error\":{\"message\":\"prompt text with sk-secret-key\",\"type\":\"invalid_request_error\",\"param\":\"max_tokens\"}}";
    TransportException ex400 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            json.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe400 = OpenAiChatErrorMapper.mapTransportException(ex400);
    assertEquals(
        "OpenAI invalid request (type=invalid_request_error, param=max_tokens)",
        pe400.getMessage());
    assertFalse(pe400.getMessage().contains("prompt text"));
    assertFalse(pe400.getMessage().contains("sk-secret"));

    TransportException ex401 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            401,
            json.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(
        OpenAiChatErrorMapper.MSG_AUTH,
        OpenAiChatErrorMapper.mapTransportException(ex401).getMessage());
  }

  @Test
  void testSseSafeErrorEnvelope() throws Exception {
    String json =
        "{\"error\":{\"message\":\"secret prompt\",\"type\":\"invalid_request_error\",\"param\":\"messages[0].role\"}}";
    ProviderException pe = OpenAiChatErrorMapper.mapSseErrorEnvelope(MAPPER.readTree(json));
    assertNotNull(pe);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, pe.kind());
    assertEquals(
        "OpenAI invalid request (type=invalid_request_error, param=messages)", pe.getMessage());
    assertFalse(pe.getMessage().contains("secret prompt"));
  }
}
