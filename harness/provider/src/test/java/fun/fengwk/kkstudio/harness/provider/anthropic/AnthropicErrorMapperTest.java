package fun.fengwk.kkstudio.harness.provider.anthropic;

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

/** 验证 AnthropicErrorMapper 错误分类及脱敏保证。 */
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

    TransportException io = new TransportException(TransportErrorKind.IO, "connection reset");
    ProviderException exIo = AnthropicErrorMapper.mapTransportException(io);
    assertEquals(ProviderErrorKind.TRANSIENT, exIo.kind());

    TransportException invalidResp =
        new TransportException(TransportErrorKind.INVALID_RESPONSE, "malformed utf8");
    ProviderException exInvalidResp = AnthropicErrorMapper.mapTransportException(invalidResp);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exInvalidResp.kind());
  }

  @Test
  void mapsHttpStatusAndErrorEnvelopesDeterministically() {
    // 401 / 403 / auth
    assertMappedStatus(
        401,
        "{\"error\":{\"type\":\"authentication_error\",\"message\":\"secret_key\"}}",
        ProviderErrorKind.AUTHENTICATION,
        AnthropicErrorMapper.MSG_AUTH);
    assertMappedStatus(
        403,
        "{\"error\":{\"type\":\"permission_error\"}}",
        ProviderErrorKind.AUTHENTICATION,
        AnthropicErrorMapper.MSG_AUTH);

    // 402 / billing
    assertMappedStatus(
        402,
        "{\"error\":{\"type\":\"billing_error\"}}",
        ProviderErrorKind.BILLING,
        AnthropicErrorMapper.MSG_BILLING);

    // 413 / request_too_large -> INVALID_REQUEST
    assertMappedStatus(
        413,
        "{\"error\":{\"type\":\"request_too_large\"}}",
        ProviderErrorKind.INVALID_REQUEST,
        AnthropicErrorMapper.MSG_INVALID_REQUEST);

    // context overflow -> OVERFLOW
    assertMappedStatus(
        400,
        "{\"error\":{\"type\":\"model_context_window_exceeded\"}}",
        ProviderErrorKind.OVERFLOW,
        AnthropicErrorMapper.MSG_OVERFLOW);

    // 429 / overloaded / 5xx
    assertMappedStatus(
        429,
        "{\"error\":{\"type\":\"rate_limit_error\"}}",
        ProviderErrorKind.TRANSIENT,
        AnthropicErrorMapper.MSG_TRANSIENT);
    assertMappedStatus(
        500,
        "{\"error\":{\"type\":\"api_error\"}}",
        ProviderErrorKind.TRANSIENT,
        AnthropicErrorMapper.MSG_TRANSIENT);
    assertMappedStatus(
        529,
        "{\"error\":{\"type\":\"overloaded_error\"}}",
        ProviderErrorKind.TRANSIENT,
        AnthropicErrorMapper.MSG_TRANSIENT);

    // 400 / 404 / other 4xx
    assertMappedStatus(
        400,
        "{\"error\":{\"type\":\"invalid_request_error\"}}",
        ProviderErrorKind.INVALID_REQUEST,
        AnthropicErrorMapper.MSG_INVALID_REQUEST);
    assertMappedStatus(
        404,
        "{\"error\":{\"type\":\"not_found_error\"}}",
        ProviderErrorKind.INVALID_REQUEST,
        AnthropicErrorMapper.MSG_INVALID_REQUEST);
  }

  @Test
  void sanitizesAndNeverLeaksRawMessageOrCause() {
    String sensitiveBody =
        "{\"error\":{\"type\":\"authentication_error\",\"message\":\"sk-ant-api03-VERY-SECRET-KEY-123456789\"}}";
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "internal raw message with sensitive info",
            401,
            sensitiveBody.getBytes(StandardCharsets.UTF_8),
            null,
            new RuntimeException("secret cause"));

    ProviderException mapped = AnthropicErrorMapper.mapTransportException(ex);
    assertNotNull(mapped);
    assertEquals(ProviderErrorKind.AUTHENTICATION, mapped.kind());
    assertEquals(AnthropicErrorMapper.MSG_AUTH, mapped.getMessage());
    assertNull(
        mapped.getCause(), "cause must be null to prevent sensitive stack or exception leaks");
    assertFalse(mapped.getMessage().contains("sk-ant-api03"));
    assertFalse(mapped.getMessage().contains("internal raw"));
  }

  @Test
  void handlesNullAndMalformedExceptionsGracefully() {
    ProviderException nullEx = AnthropicErrorMapper.mapTransportException(null);
    assertEquals(ProviderErrorKind.TRANSIENT, nullEx.kind());

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

    // HTTP 状态带 null body
    TransportException nullBody =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 400, null, null, null);
    ProviderException ex400 = AnthropicErrorMapper.mapTransportException(nullBody);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex400.kind());

    // 未知 HTTP 状态 (如 200 出现在异常中)
    TransportException weirdStatus =
        new TransportException(TransportErrorKind.HTTP_STATUS, "status", 200, null, null, null);
    ProviderException exWeird = AnthropicErrorMapper.mapTransportException(weirdStatus);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exWeird.kind());
  }

  @Test
  void mapsSseErrorEnvelopeBranches() throws Exception {
    assertNull(AnthropicErrorMapper.mapTransportException(null).getCause());

    // null / non-object envelope
    ProviderException exNull = AnthropicErrorMapper.mapSseErrorEnvelope(null);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exNull.kind());

    ProviderException exArray = AnthropicErrorMapper.mapSseErrorEnvelope(MAPPER.readTree("[]"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exArray.kind());

    // top-level type
    ProviderException exTopType =
        AnthropicErrorMapper.mapSseErrorEnvelope(
            MAPPER.readTree("{\"type\":\"rate_limit_error\"}"));
    assertEquals(ProviderErrorKind.TRANSIENT, exTopType.kind());

    // error.type
    ProviderException exNested =
        AnthropicErrorMapper.mapSseErrorEnvelope(
            MAPPER.readTree("{\"error\":{\"type\":\"authentication_error\"}}"));
    assertEquals(ProviderErrorKind.AUTHENTICATION, exNested.kind());

    // unknown type
    ProviderException exUnknown =
        AnthropicErrorMapper.mapSseErrorEnvelope(
            MAPPER.readTree("{\"error\":{\"type\":\"some_bizarre_error\"}}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exUnknown.kind());
  }

  private static void assertMappedStatus(
      int status, String body, ProviderErrorKind expectedKind, String expectedMsg) {
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
    assertEquals(expectedMsg, mapped.getMessage());
  }
}
