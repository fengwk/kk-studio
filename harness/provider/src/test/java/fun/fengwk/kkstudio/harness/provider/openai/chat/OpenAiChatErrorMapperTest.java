package fun.fengwk.kkstudio.harness.provider.openai.chat;

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
import java.util.List;
import java.util.Map;

/** 测试意图：验证 OpenAI Chat 异常映射与客户端所见即所得机制，确保错误分类正确且完整保留响应正文。 */
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
    assertEquals("OpenAI request timed out", ex1.getMessage());

    ProviderException ex2 =
        OpenAiChatErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.IO, "io error"));
    assertNotNull(ex2);
    assertEquals(ProviderErrorKind.TRANSIENT, ex2.kind());
    assertEquals("OpenAI I/O error", ex2.getMessage());
  }

  @Test
  @DisplayName("HTTP 状态码与 error payload 映射分类及完整正文保留")
  void mapHttpStatusAndTypes() {
    // 401 认证失败
    String body401 = "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"Incorrect key\"}}";
    TransportException ex401 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            401,
            body401.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe401 = OpenAiChatErrorMapper.mapTransportException(ex401);
    assertEquals(ProviderErrorKind.AUTHENTICATION, pe401.kind());
    assertEquals("HTTP 401\n" + body401, pe401.getMessage());

    // 402 / insufficient_quota 配额不足
    String body402 = "{\"error\":{\"type\":\"insufficient_quota\"}}";
    TransportException ex402 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            429,
            body402.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException pe402 = OpenAiChatErrorMapper.mapTransportException(ex402);
    assertEquals(ProviderErrorKind.BILLING, pe402.kind());
    assertEquals("HTTP 429\n" + body402, pe402.getMessage());

    // context_length_exceeded
    String bodyOverflow = "{\"error\":{\"code\":\"context_length_exceeded\"}}";
    TransportException exOverflow =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            bodyOverflow.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peOverflow = OpenAiChatErrorMapper.mapTransportException(exOverflow);
    assertEquals(ProviderErrorKind.OVERFLOW, peOverflow.kind());
    assertEquals("HTTP 400\n" + bodyOverflow, peOverflow.getMessage());

    // 413
    TransportException ex413 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "status", 413, new byte[0], null, null);
    ProviderException pe413 = OpenAiChatErrorMapper.mapTransportException(ex413);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, pe413.kind());
    assertEquals("HTTP 413: " + OpenAiChatErrorMapper.MSG_INVALID_REQUEST, pe413.getMessage());

    // 429 rate limit
    TransportException ex429 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "status", 429, new byte[0], null, null);
    ProviderException pe429 = OpenAiChatErrorMapper.mapTransportException(ex429);
    assertEquals(ProviderErrorKind.TRANSIENT, pe429.kind());
    assertEquals("HTTP 429: " + OpenAiChatErrorMapper.MSG_TRANSIENT, pe429.getMessage());

    // 500 server error
    TransportException ex500 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "status", 500, new byte[0], null, null);
    ProviderException pe500 = OpenAiChatErrorMapper.mapTransportException(ex500);
    assertEquals(ProviderErrorKind.TRANSIENT, pe500.kind());
    assertEquals("HTTP 500: " + OpenAiChatErrorMapper.MSG_TRANSIENT, pe500.getMessage());

    // 显式 request_too_large 保持优先于服务端状态码
    String bodyTooLarge = "{\"error\":{\"code\":\"request_too_large\"}}";
    TransportException ex500TooLarge =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            500,
            bodyTooLarge.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peTooLarge = OpenAiChatErrorMapper.mapTransportException(ex500TooLarge);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, peTooLarge.kind());
    assertEquals("HTTP 500\n" + bodyTooLarge, peTooLarge.getMessage());
  }

  @Test
  @DisplayName("SSE error envelope 节点完整保留与全字段包含")
  void mapSseErrorEnvelope() throws Exception {
    String json =
        "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"},\"custom_id\":123}";
    ProviderException pe = OpenAiChatErrorMapper.mapSseErrorEnvelope(MAPPER.readTree(json));
    assertEquals(ProviderErrorKind.TRANSIENT, pe.kind());
    assertEquals(MAPPER.readTree(json).toString(), pe.getMessage());
    assertTrue(pe.getMessage().contains("Rate limit reached"));
    assertTrue(pe.getMessage().contains("custom_id"));

    // 顶级 type
    String json2 = "{\"type\":\"insufficient_quota\",\"details\":\"balance zero\"}";
    ProviderException pe2 = OpenAiChatErrorMapper.mapSseErrorEnvelope(MAPPER.readTree(json2));
    assertEquals(ProviderErrorKind.BILLING, pe2.kind());
    assertEquals(MAPPER.readTree(json2).toString(), pe2.getMessage());

    // 错误节点为空回退
    ProviderException peNull = OpenAiChatErrorMapper.mapSseErrorEnvelope(null);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, peNull.kind());
    assertEquals(OpenAiChatErrorMapper.MSG_INVALID_RESPONSE, peNull.getMessage());

    // 非对象 JSON 数组保留其 node string 并归类为 INVALID_RESPONSE
    ProviderException peArray =
        OpenAiChatErrorMapper.mapSseErrorEnvelope(MAPPER.readTree("[\"item1\",\"item2\"]"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, peArray.kind());
    assertEquals("[\"item1\",\"item2\"]", peArray.getMessage());
  }

  @Test
  @DisplayName("异常映射完整边界与私有构造器")
  void testErrorMapperCompleteBoundaries() throws Exception {
    // null 异常
    ProviderException exNull = OpenAiChatErrorMapper.mapTransportException(null);
    assertEquals(ProviderErrorKind.TRANSIENT, exNull.kind());
    assertEquals(OpenAiChatErrorMapper.MSG_TRANSIENT, exNull.getMessage());

    // INVALID_RESPONSE 传输异常
    TransportException exInv =
        new TransportException(TransportErrorKind.INVALID_RESPONSE, "invalid response");
    ProviderException peInv = OpenAiChatErrorMapper.mapTransportException(exInv);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, peInv.kind());
    assertEquals(OpenAiChatErrorMapper.MSG_INVALID_RESPONSE, peInv.getMessage());

    // 损坏的 errorBodyBytes 降级状态码处理并原样输出正文
    TransportException exCorrupted =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            "{not-json".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peCorrupted = OpenAiChatErrorMapper.mapTransportException(exCorrupted);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, peCorrupted.kind());
    assertEquals("HTTP 400\n{not-json", peCorrupted.getMessage());

    // 非对象的 JSON 数组降级状态码处理并原样输出正文
    TransportException exArrayJson =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            "[\"item\"]".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peArrayJson = OpenAiChatErrorMapper.mapTransportException(exArrayJson);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, peArrayJson.kind());
    assertEquals("HTTP 400\n[\"item\"]", peArrayJson.getMessage());

    // 403 Forbidden (无 body)
    TransportException ex403 =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 403, null, null, null);
    ProviderException pe403 = OpenAiChatErrorMapper.mapTransportException(ex403);
    assertEquals(ProviderErrorKind.AUTHENTICATION, pe403.kind());
    assertEquals("HTTP 403: " + OpenAiChatErrorMapper.MSG_AUTH, pe403.getMessage());

    // 402 Billing (无 body)
    TransportException ex402Direct =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 402, null, null, null);
    ProviderException pe402 = OpenAiChatErrorMapper.mapTransportException(ex402Direct);
    assertEquals(ProviderErrorKind.BILLING, pe402.kind());
    assertEquals("HTTP 402: " + OpenAiChatErrorMapper.MSG_BILLING, pe402.getMessage());

    // 404 Invalid Request (无 body)
    TransportException ex404 =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 404, null, null, null);
    ProviderException pe404 = OpenAiChatErrorMapper.mapTransportException(ex404);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, pe404.kind());
    assertEquals("HTTP 404: " + OpenAiChatErrorMapper.MSG_INVALID_REQUEST, pe404.getMessage());

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

  @Test
  @DisplayName("完整保留空白、非 JSON 与多行 Unicode 正文")
  void testExactBodyWithWhitespaceAndNonJson() {
    // 包含前后空白的正文必须原样保留
    String whitespaceBody = "   \n  {\"error\": \"spaces\"}  \t\n";
    TransportException exWhitespace =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            whitespaceBody.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peWhitespace = OpenAiChatErrorMapper.mapTransportException(exWhitespace);
    assertEquals("HTTP 400\n" + whitespaceBody, peWhitespace.getMessage());

    // 多行 Unicode 与 emoji
    String unicodeBody = "错误信息：请求参数错误\nDetail: 详细原因 \uD83D\uDEAB 超限\nLine 3: 提示";
    TransportException exUnicode =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            unicodeBody.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peUnicode = OpenAiChatErrorMapper.mapTransportException(exUnicode);
    assertEquals("HTTP 400\n" + unicodeBody, peUnicode.getMessage());

    // HTML / 非 JSON 错误正文
    String html = "<html><body><h1>502 Bad Gateway</h1></body></html>";
    TransportException exHtml =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            502,
            html.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peHtml = OpenAiChatErrorMapper.mapTransportException(exHtml);
    assertEquals(ProviderErrorKind.TRANSIENT, peHtml.kind());
    assertEquals("HTTP 502\n" + html, peHtml.getMessage());
  }

  @Test
  @DisplayName("截断错误正文追加截断标记")
  void testTruncatedBodyHasMarker() {
    byte[] body = "partial error json body...".getBytes(StandardCharsets.UTF_8);
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            body,
            true, // truncated
            null,
            null);
    ProviderException pe = OpenAiChatErrorMapper.mapTransportException(ex);
    assertEquals(
        "HTTP 400\npartial error json body..." + ProviderErrorHelper.TRUNCATION_MARKER,
        pe.getMessage());
  }

  @Test
  @DisplayName("安全防护：请求 URI、标头、凭证及 cause 绝不上抛")
  void testSecurityGuards() {
    String sensitiveTransportMsg =
        "POST https://api.openai.com/v1/chat/completions with Bearer fake-secret-token";
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            sensitiveTransportMsg,
            401,
            "{\"error\":{\"code\":\"invalid_api_key\"}}".getBytes(StandardCharsets.UTF_8),
            false,
            Map.of("Authorization", List.of("Bearer fake-secret-token")),
            new RuntimeException("cause with fake-secret-token"));

    ProviderException pe = OpenAiChatErrorMapper.mapTransportException(ex);
    assertNotNull(pe);
    assertEquals(ProviderErrorKind.AUTHENTICATION, pe.kind());
    assertEquals("HTTP 401\n{\"error\":{\"code\":\"invalid_api_key\"}}", pe.getMessage());
    assertFalse(pe.getMessage().contains("https://"));
    assertFalse(pe.getMessage().contains("sensitiveTransportMsg"));
    assertFalse(pe.getMessage().contains("fake-secret-token"));
    assertNull(pe.getCause());
  }
}
