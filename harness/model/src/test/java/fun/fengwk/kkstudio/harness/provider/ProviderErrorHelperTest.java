package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.IntNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.nio.charset.StandardCharsets;

/** 测试意图：验证 ProviderErrorHelper 的 HTTP 错误消息格式化、截断标记与静默错误判断逻辑。 */
class ProviderErrorHelperTest {

  @Test
  @DisplayName("isSilentTransportKind 正确识别静默传输类型")
  void testIsSilentTransportKind() {
    assertTrue(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.CANCELLED));

    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.TIMEOUT));
    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.IO));
    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.HTTP_STATUS));
    assertFalse(ProviderErrorHelper.isSilentTransportKind(TransportErrorKind.INVALID_RESPONSE));
  }

  @Test
  @DisplayName("formatHttpErrorMessage: 正文缺失时回退为 HTTP 状态码与 fallback")
  void testFormatHttpErrorMessageWithoutBody() {
    String fallback = "Default fallback error";

    // null body with positive status
    assertEquals(
        "HTTP 401: " + fallback,
        ProviderErrorHelper.formatHttpErrorMessage(401, null, false, fallback));

    // empty body with positive status
    assertEquals(
        "HTTP 500: " + fallback,
        ProviderErrorHelper.formatHttpErrorMessage(500, "", false, fallback));

    // null body with zero or negative status
    assertEquals(fallback, ProviderErrorHelper.formatHttpErrorMessage(0, null, false, fallback));
    assertEquals(fallback, ProviderErrorHelper.formatHttpErrorMessage(-1, "", false, fallback));
  }

  @Test
  @DisplayName("formatHttpErrorMessage: 完整保留原始正文及空白、非 JSON 与截断标记")
  void testFormatHttpErrorMessageWithBody() {
    String fallback = "Fallback";

    // normal body without truncation
    String body = "{\"error\":\"test\"}";
    assertEquals(
        "HTTP 400\n" + body,
        ProviderErrorHelper.formatHttpErrorMessage(400, body, false, fallback));

    // normal body with truncation
    assertEquals(
        "HTTP 400\n" + body + ProviderErrorHelper.TRUNCATION_MARKER,
        ProviderErrorHelper.formatHttpErrorMessage(400, body, true, fallback));

    // whitespace body must be preserved verbatim
    String whitespaceBody = "   \n\t  ";
    assertEquals(
        "HTTP 400\n" + whitespaceBody,
        ProviderErrorHelper.formatHttpErrorMessage(400, whitespaceBody, false, fallback));

    // non-JSON body
    String htmlBody = "<html><body>502 Bad Gateway</body></html>";
    assertEquals(
        "HTTP 502\n" + htmlBody,
        ProviderErrorHelper.formatHttpErrorMessage(502, htmlBody, false, fallback));
  }

  @Test
  @DisplayName("formatHttpErrorMessage(TransportException): 委托与边界检查")
  void testFormatHttpErrorMessageFromException() {
    String fallback = "Fallback";

    // null exception returns fallback
    assertEquals(
        fallback, ProviderErrorHelper.formatHttpErrorMessage((TransportException) null, fallback));

    // exception without body
    TransportException exNoBody =
        new TransportException(TransportErrorKind.HTTP_STATUS, "msg", 401, null, null, null);
    assertEquals(
        "HTTP 401: " + fallback, ProviderErrorHelper.formatHttpErrorMessage(exNoBody, fallback));

    // exception with body and truncation
    byte[] bodyBytes = "truncated error response".getBytes(StandardCharsets.UTF_8);
    TransportException exTruncated =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "msg", 500, bodyBytes, true, null, null);
    assertEquals(
        "HTTP 500\ntruncated error response" + ProviderErrorHelper.TRUNCATION_MARKER,
        ProviderErrorHelper.formatHttpErrorMessage(exTruncated, fallback));
  }

  @Test
  @DisplayName("classifyHttpStatus: 状态码分类覆盖所有区间")
  void testClassifyHttpStatus() {
    assertEquals(ProviderErrorKind.AUTHENTICATION, ProviderErrorHelper.classifyHttpStatus(401));
    assertEquals(ProviderErrorKind.AUTHENTICATION, ProviderErrorHelper.classifyHttpStatus(403));
    assertEquals(ProviderErrorKind.BILLING, ProviderErrorHelper.classifyHttpStatus(402));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ProviderErrorHelper.classifyHttpStatus(413));
    assertEquals(ProviderErrorKind.TRANSIENT, ProviderErrorHelper.classifyHttpStatus(429));
    assertEquals(ProviderErrorKind.TRANSIENT, ProviderErrorHelper.classifyHttpStatus(500));
    assertEquals(ProviderErrorKind.TRANSIENT, ProviderErrorHelper.classifyHttpStatus(503));
    assertEquals(ProviderErrorKind.TRANSIENT, ProviderErrorHelper.classifyHttpStatus(599));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ProviderErrorHelper.classifyHttpStatus(400));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ProviderErrorHelper.classifyHttpStatus(404));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ProviderErrorHelper.classifyHttpStatus(422));
    assertEquals(ProviderErrorKind.TRANSIENT, ProviderErrorHelper.classifyHttpStatus(600));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ProviderErrorHelper.classifyHttpStatus(200));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ProviderErrorHelper.classifyHttpStatus(301));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ProviderErrorHelper.classifyHttpStatus(0));
  }

  @Test
  @DisplayName("FallbackMessages: 各类别 fallback 查找")
  void testFallbackMessages() {
    ProviderErrorHelper.FallbackMessages messages =
        new ProviderErrorHelper.FallbackMessages(
            "auth", "billing", "overflow", "invalid_req", "transient", "invalid_resp");
    assertEquals("auth", messages.fallbackForKind(ProviderErrorKind.AUTHENTICATION));
    assertEquals("billing", messages.fallbackForKind(ProviderErrorKind.BILLING));
    assertEquals("overflow", messages.fallbackForKind(ProviderErrorKind.OVERFLOW));
    assertEquals("invalid_req", messages.fallbackForKind(ProviderErrorKind.INVALID_REQUEST));
    assertEquals("transient", messages.fallbackForKind(ProviderErrorKind.TRANSIENT));
    assertEquals("invalid_resp", messages.fallbackForKind(ProviderErrorKind.INVALID_RESPONSE));
    assertEquals("invalid_resp", messages.fallbackForKind(ProviderErrorKind.CANCELLED));
    assertEquals("invalid_resp", messages.fallbackForKind(ProviderErrorKind.NO_AVAILABLE_MODEL));
  }

  @Test
  @DisplayName("mapTransportException: 传输异常映射骨架覆盖所有分支")
  void testMapTransportException() {
    ProviderErrorHelper.FallbackMessages fallbacks =
        new ProviderErrorHelper.FallbackMessages(
            "auth", "billing", "overflow", "invalid_req", "transient", "invalid_resp");

    // null exception
    ProviderException exNull =
        ProviderErrorHelper.mapTransportException(
            null, "TestProvider", ex -> ProviderErrorKind.TRANSIENT, fallbacks);
    assertEquals(ProviderErrorKind.TRANSIENT, exNull.kind());
    assertEquals("transient", exNull.getMessage());

    // CANCELLED -> null
    assertNull(
        ProviderErrorHelper.mapTransportException(
            new TransportException(TransportErrorKind.CANCELLED, "c"),
            "TestProvider",
            ex -> ProviderErrorKind.TRANSIENT,
            fallbacks));

    // TIMEOUT
    ProviderException exTimeout =
        ProviderErrorHelper.mapTransportException(
            new TransportException(TransportErrorKind.TIMEOUT, "t"),
            "TestProvider",
            ex -> ProviderErrorKind.TRANSIENT,
            fallbacks);
    assertEquals(ProviderErrorKind.TRANSIENT, exTimeout.kind());
    assertEquals("TestProvider request timed out", exTimeout.getMessage());

    // IO
    ProviderException exIo =
        ProviderErrorHelper.mapTransportException(
            new TransportException(TransportErrorKind.IO, "io"),
            "TestProvider",
            ex -> ProviderErrorKind.TRANSIENT,
            fallbacks);
    assertEquals(ProviderErrorKind.TRANSIENT, exIo.kind());
    assertEquals("TestProvider I/O error", exIo.getMessage());

    // INVALID_RESPONSE
    ProviderException exInv =
        ProviderErrorHelper.mapTransportException(
            new TransportException(TransportErrorKind.INVALID_RESPONSE, "inv"),
            "TestProvider",
            ex -> ProviderErrorKind.TRANSIENT,
            fallbacks);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exInv.kind());
    assertEquals("invalid_resp", exInv.getMessage());

    // HTTP_STATUS
    byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
    TransportException httpEx =
        new TransportException(TransportErrorKind.HTTP_STATUS, "err", 401, body, null, null);
    ProviderException exHttp =
        ProviderErrorHelper.mapTransportException(
            httpEx, "TestProvider", ex -> ProviderErrorKind.AUTHENTICATION, fallbacks);
    assertEquals(ProviderErrorKind.AUTHENTICATION, exHttp.kind());
    assertEquals("HTTP 401\n{\"error\":\"unauthorized\"}", exHttp.getMessage());
    assertEquals(401, exHttp.httpStatus());
  }

  @Test
  @DisplayName("mapSseErrorEnvelope: SSE envelope 映射骨架")
  void testMapSseErrorEnvelope() {
    ObjectMapper mapper = JsonMapper.builder().build();

    // null node
    ProviderException exNull =
        ProviderErrorHelper.mapSseErrorEnvelope(
            null, node -> ProviderErrorKind.INVALID_RESPONSE, "fallback_inv");
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exNull.kind());
    assertEquals("fallback_inv", exNull.getMessage());
    assertNull(exNull.httpStatus());

    // non-object node
    ProviderException exNonObj =
        ProviderErrorHelper.mapSseErrorEnvelope(
            new IntNode(123), node -> ProviderErrorKind.INVALID_RESPONSE, "fallback_inv");
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exNonObj.kind());
    assertEquals("123", exNonObj.getMessage());
    assertNull(exNonObj.httpStatus());

    // 无状态协议：完整保留 envelope 文本，状态保持 null
    JsonNode obj = mapper.createObjectNode().put("message", "custom_err");
    ProviderException exObj =
        ProviderErrorHelper.mapSseErrorEnvelope(
            obj, node -> ProviderErrorKind.TRANSIENT, "fallback_inv");
    assertEquals(ProviderErrorKind.TRANSIENT, exObj.kind());
    assertEquals("{\"message\":\"custom_err\"}", exObj.getMessage());
    assertNull(exObj.httpStatus());

    // 带状态协议：提取 envelope 中的状态码
    ProviderException exStatus =
        ProviderErrorHelper.mapSseErrorEnvelope(
            obj, node -> ProviderErrorKind.TRANSIENT, node -> 429, "fallback_inv");
    assertEquals(ProviderErrorKind.TRANSIENT, exStatus.kind());
    assertEquals("{\"message\":\"custom_err\"}", exStatus.getMessage());
    assertEquals(429, exStatus.httpStatus());
  }
}
