package fun.fengwk.kkstudio.harness.provider.anthropic;

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

/** 测试意图：验证 AnthropicErrorMapper 错误分类及客户端所见即所得错误正文保留保证。 */
class AnthropicErrorMapperTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void mapsCancelledAndExecutorRejectedAndCallbackFailedToNull() {
    TransportException cancelled =
        new TransportException(TransportErrorKind.CANCELLED, "cancelled by user");
    assertNull(AnthropicErrorMapper.mapTransportException(cancelled));

    TransportException rejected =
        new TransportException(TransportErrorKind.EXECUTOR_REJECTED, "rejected");
    assertNull(AnthropicErrorMapper.mapTransportException(rejected));

    TransportException callbackFailed =
        new TransportException(TransportErrorKind.CALLBACK_FAILED, "callback failed");
    assertNull(AnthropicErrorMapper.mapTransportException(callbackFailed));
  }

  @Test
  void mapsTransportKindsToTransientOrInvalidResponse() {
    TransportException timeout = new TransportException(TransportErrorKind.TIMEOUT, "timeout");
    ProviderException exTimeout = AnthropicErrorMapper.mapTransportException(timeout);
    assertEquals(ProviderErrorKind.TRANSIENT, exTimeout.kind());
    assertEquals("Anthropic request timed out", exTimeout.getMessage());

    TransportException io = new TransportException(TransportErrorKind.IO, "connection reset");
    ProviderException exIo = AnthropicErrorMapper.mapTransportException(io);
    assertEquals(ProviderErrorKind.TRANSIENT, exIo.kind());
    assertEquals("Anthropic I/O error", exIo.getMessage());

    TransportException invalidResp =
        new TransportException(TransportErrorKind.INVALID_RESPONSE, "malformed utf8");
    ProviderException exInvalidResp = AnthropicErrorMapper.mapTransportException(invalidResp);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exInvalidResp.kind());
    assertEquals(AnthropicErrorMapper.MSG_INVALID_RESPONSE, exInvalidResp.getMessage());
  }

  @Test
  void mapsHttpStatusAndErrorEnvelopesDeterministically() {
    // 401 / 403 / auth
    assertMappedStatus(
        401,
        "{\"error\":{\"type\":\"authentication_error\",\"message\":\"secret_key\"}}",
        ProviderErrorKind.AUTHENTICATION);
    assertMappedStatus(
        403, "{\"error\":{\"type\":\"permission_error\"}}", ProviderErrorKind.AUTHENTICATION);

    // 402 / billing
    assertMappedStatus(402, "{\"error\":{\"type\":\"billing_error\"}}", ProviderErrorKind.BILLING);

    // 413 / request_too_large -> INVALID_REQUEST
    assertMappedStatus(
        413, "{\"error\":{\"type\":\"request_too_large\"}}", ProviderErrorKind.INVALID_REQUEST);

    // context overflow -> OVERFLOW
    assertMappedStatus(
        400,
        "{\"error\":{\"type\":\"model_context_window_exceeded\"}}",
        ProviderErrorKind.OVERFLOW);

    // 429 / overloaded / 5xx
    assertMappedStatus(
        429, "{\"error\":{\"type\":\"rate_limit_error\"}}", ProviderErrorKind.TRANSIENT);
    assertMappedStatus(500, "{\"error\":{\"type\":\"api_error\"}}", ProviderErrorKind.TRANSIENT);
    assertMappedStatus(
        529, "{\"error\":{\"type\":\"overloaded_error\"}}", ProviderErrorKind.TRANSIENT);

    // 400 / 404 / other 4xx
    assertMappedStatus(
        400, "{\"error\":{\"type\":\"invalid_request_error\"}}", ProviderErrorKind.INVALID_REQUEST);
    assertMappedStatus(
        404, "{\"error\":{\"type\":\"not_found_error\"}}", ProviderErrorKind.INVALID_REQUEST);
  }

  @Test
  void retainsRawBodyAndNeverLeaksInternalTransportMessageOrCause() {
    String rawBody =
        "{\"error\":{\"type\":\"authentication_error\",\"message\":\"fake-key-123456789\"}}";
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "internal raw message with sensitive info",
            401,
            rawBody.getBytes(StandardCharsets.UTF_8),
            false,
            null,
            new RuntimeException("secret cause"));

    ProviderException mapped = AnthropicErrorMapper.mapTransportException(ex);
    assertNotNull(mapped);
    assertEquals(ProviderErrorKind.AUTHENTICATION, mapped.kind());
    assertEquals("HTTP 401\n" + rawBody, mapped.getMessage());
    assertNull(
        mapped.getCause(), "cause must be null to prevent sensitive stack or exception leaks");
    assertTrue(mapped.getMessage().contains("fake-key-123456789"));
    assertFalse(mapped.getMessage().contains("internal raw message"));
  }

  @Test
  void handlesNullAndMalformedExceptionsGracefully() {
    ProviderException nullEx = AnthropicErrorMapper.mapTransportException(null);
    assertEquals(ProviderErrorKind.TRANSIENT, nullEx.kind());
    assertEquals(AnthropicErrorMapper.MSG_TRANSIENT, nullEx.getMessage());

    // HTTP 状态带损坏的非 JSON body
    TransportException malformedBody =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            503,
            "not json".getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException ex503 = AnthropicErrorMapper.mapTransportException(malformedBody);
    assertEquals(ProviderErrorKind.TRANSIENT, ex503.kind());
    assertEquals("HTTP 503\nnot json", ex503.getMessage());

    // HTTP 状态带 null body 回退
    TransportException nullBody =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 400, null, null, null);
    ProviderException ex400 = AnthropicErrorMapper.mapTransportException(nullBody);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex400.kind());
    assertEquals("HTTP 400: " + AnthropicErrorMapper.MSG_INVALID_REQUEST, ex400.getMessage());

    // 未知 HTTP 状态 (如 200 出现在异常中且无 body)
    TransportException weirdStatus =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 200, null, null, null);
    ProviderException exWeird = AnthropicErrorMapper.mapTransportException(weirdStatus);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exWeird.kind());
    assertEquals("HTTP 200: " + AnthropicErrorMapper.MSG_INVALID_RESPONSE, exWeird.getMessage());
  }

  @Test
  void mapsSseErrorEnvelopeBranches() throws Exception {
    assertNull(AnthropicErrorMapper.mapTransportException(null).getCause());

    // null envelope
    ProviderException exNull = AnthropicErrorMapper.mapSseErrorEnvelope(null);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exNull.kind());
    assertEquals(AnthropicErrorMapper.MSG_INVALID_RESPONSE, exNull.getMessage());

    // non-object envelope 保留其 node 字符串
    ProviderException exArray = AnthropicErrorMapper.mapSseErrorEnvelope(MAPPER.readTree("[]"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exArray.kind());
    assertEquals("[]", exArray.getMessage());

    // top-level type
    var topTypeNode = MAPPER.readTree("{\"type\":\"rate_limit_error\",\"extra_meta\":true}");
    ProviderException exTopType = AnthropicErrorMapper.mapSseErrorEnvelope(topTypeNode);
    assertEquals(ProviderErrorKind.TRANSIENT, exTopType.kind());
    assertEquals(topTypeNode.toString(), exTopType.getMessage());
    assertTrue(exTopType.getMessage().contains("extra_meta"));

    // error.type
    var nestedNode =
        MAPPER.readTree("{\"error\":{\"type\":\"authentication_error\",\"detail\":\"bad key\"}}");
    ProviderException exNested = AnthropicErrorMapper.mapSseErrorEnvelope(nestedNode);
    assertEquals(ProviderErrorKind.AUTHENTICATION, exNested.kind());
    assertEquals(nestedNode.toString(), exNested.getMessage());

    // unknown type
    var unknownNode = MAPPER.readTree("{\"error\":{\"type\":\"some_bizarre_error\"}}");
    ProviderException exUnknown = AnthropicErrorMapper.mapSseErrorEnvelope(unknownNode);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exUnknown.kind());
    assertEquals(unknownNode.toString(), exUnknown.getMessage());
  }

  @Test
  @DisplayName("完整保留空白、多行 Unicode 与截断标记")
  void testWhitespaceUnicodeAndTruncation() {
    String whitespaceBody = "  \n\t  {\"error\":{\"type\":\"invalid_request_error\"}}  \n";
    TransportException exWhitespace =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            400,
            whitespaceBody.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peWhitespace = AnthropicErrorMapper.mapTransportException(exWhitespace);
    assertEquals("HTTP 400\n" + whitespaceBody, peWhitespace.getMessage());

    String unicodeBody = "Anthropic 错误：\n类型：服务过载 \uD83D\uDEA8\n提示：请稍后再试";
    TransportException exUnicode =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status",
            529,
            unicodeBody.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException peUnicode = AnthropicErrorMapper.mapTransportException(exUnicode);
    assertEquals(ProviderErrorKind.TRANSIENT, peUnicode.kind());
    assertEquals("HTTP 529\n" + unicodeBody, peUnicode.getMessage());

    byte[] partialBytes =
        "{\"type\":\"error\",\"error\":{\"type\":\"api_err...".getBytes(StandardCharsets.UTF_8);
    TransportException exTruncated =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "status", 500, partialBytes, true, null, null);
    ProviderException peTruncated = AnthropicErrorMapper.mapTransportException(exTruncated);
    assertEquals(
        "HTTP 500\n"
            + new String(partialBytes, StandardCharsets.UTF_8)
            + ProviderErrorHelper.TRUNCATION_MARKER,
        peTruncated.getMessage());
  }

  private static void assertMappedStatus(int status, String body, ProviderErrorKind expectedKind) {
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "status error",
            status,
            body.getBytes(StandardCharsets.UTF_8),
            null,
            null);
    ProviderException mapped = AnthropicErrorMapper.mapTransportException(ex);
    assertNotNull(mapped);
    assertEquals(expectedKind, mapped.kind());
    assertEquals("HTTP " + status + "\n" + body, mapped.getMessage());
  }
}
