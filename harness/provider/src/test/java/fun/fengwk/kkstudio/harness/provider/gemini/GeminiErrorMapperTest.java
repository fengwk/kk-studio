package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.TransportErrorKind;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Gemini 错误码分类与安全脱敏测试。 */
class GeminiErrorMapperTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 验证 HTTP 400 及 INVALID_ARGUMENT 规范映射为 INVALID_REQUEST。 */
  @Test
  void maps400BadRequest() {
    String errorJson =
        """
        {
          "error": {
            "code": 400,
            "message": "Invalid field value",
            "status": "INVALID_ARGUMENT"
          }
        }
        """;
    ProviderException ex = GeminiErrorMapper.fromHttp(400, errorJson);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("Gemini invalid request"));
  }

  /** 验证 HTTP 401 及 403 映射为认证失败。 */
  @Test
  void maps401And403AuthErrors() {
    String unauth =
        "{\"error\":{\"code\":401,\"message\":\"API key not valid\",\"status\":\"UNAUTHENTICATED\"}}";
    ProviderException ex1 = GeminiErrorMapper.fromHttp(401, unauth);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex1.kind());

    String denied =
        "{\"error\":{\"code\":403,\"message\":\"Quota exceeded\",\"status\":\"PERMISSION_DENIED\"}}";
    ProviderException ex2 = GeminiErrorMapper.fromHttp(403, denied);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex2.kind());
  }

  /** 验证 HTTP 402 映射为 BILLING。 */
  @Test
  void maps402BillingError() {
    ProviderException ex = GeminiErrorMapper.fromHttp(402, "{}");
    assertEquals(ProviderErrorKind.BILLING, ex.kind());
  }

  /** 验证 HTTP 404 映射为 INVALID_REQUEST。 */
  @Test
  void maps404NotFound() {
    String notFound =
        "{\"error\":{\"code\":404,\"message\":\"models/gemini-unknown not found\",\"status\":\"NOT_FOUND\"}}";
    ProviderException ex = GeminiErrorMapper.fromHttp(404, notFound);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }

  /** 验证 HTTP 429 映射为 TRANSIENT。 */
  @Test
  void maps429ResourceExhausted() {
    String rateLimit =
        "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
    ProviderException ex = GeminiErrorMapper.fromHttp(429, rateLimit);
    assertEquals(ProviderErrorKind.TRANSIENT, ex.kind());
  }

  /** 验证 HTTP 500/503 映射为 TRANSIENT。 */
  @Test
  void maps500And503ServerErrors() {
    ProviderException ex1 =
        GeminiErrorMapper.fromHttp(500, "{\"error\":{\"status\":\"INTERNAL\"}}");
    assertEquals(ProviderErrorKind.TRANSIENT, ex1.kind());

    ProviderException ex2 =
        GeminiErrorMapper.fromHttp(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
    assertEquals(ProviderErrorKind.TRANSIENT, ex2.kind());
  }

  /** 验证底层网络与超时异常的规范映射。 */
  @Test
  void mapsTransportExceptions() {
    ProviderException timeoutEx =
        GeminiErrorMapper.fromThrowable(new TimeoutException("read timed out"));
    assertEquals(ProviderErrorKind.TRANSIENT, timeoutEx.kind());

    ProviderException sockTimeoutEx =
        GeminiErrorMapper.fromThrowable(new SocketTimeoutException("connect timed out"));
    assertEquals(ProviderErrorKind.TRANSIENT, sockTimeoutEx.kind());

    ProviderException connEx =
        GeminiErrorMapper.fromThrowable(new ConnectException("connection refused"));
    assertEquals(ProviderErrorKind.TRANSIENT, connEx.kind());

    ProviderException ioEx = GeminiErrorMapper.fromThrowable(new IOException("broken pipe"));
    assertEquals(ProviderErrorKind.TRANSIENT, ioEx.kind());

    ProviderException cancelEx =
        GeminiErrorMapper.fromThrowable(new CancellationException("client canceled"));
    assertEquals(ProviderErrorKind.CANCELLED, cancelEx.kind());

    ProviderException unknownEx =
        GeminiErrorMapper.fromThrowable(new IllegalArgumentException("runtime"));
    assertEquals(ProviderErrorKind.TRANSIENT, unknownEx.kind());
  }

  /** 验证 TransportException 各个 kind 的精确映射与静默取消。 */
  @Test
  void mapsTransportExceptionKinds() {
    assertNotNull(GeminiErrorMapper.mapTransportException(null));

    assertNull(
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.CANCELLED, "cancelled")));
    assertNull(
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.EXECUTOR_REJECTED, "rejected")));
    assertNull(
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.CALLBACK_FAILED, "callback")));

    ProviderException timeoutEx =
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.TIMEOUT, "timeout"));
    assertEquals(ProviderErrorKind.TRANSIENT, timeoutEx.kind());

    ProviderException ioEx =
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.IO, "io"));
    assertEquals(ProviderErrorKind.TRANSIENT, ioEx.kind());

    ProviderException invalidRespEx =
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.INVALID_RESPONSE, "invalid"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, invalidRespEx.kind());

    TransportException httpEx =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "server error",
            500,
            "{\"error\":{\"status\":\"INTERNAL\"}}".getBytes(StandardCharsets.UTF_8),
            Map.of());
    assertEquals(
        ProviderErrorKind.TRANSIENT, GeminiErrorMapper.mapTransportException(httpEx).kind());
  }

  /** 验证 SSE Error Envelope 的映射解析与边界情况。 */
  @Test
  void mapsSseErrorEnvelope() {
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, GeminiErrorMapper.mapSseErrorEnvelope(null).kind());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        GeminiErrorMapper.mapSseErrorEnvelope(new IntNode(123)).kind());

    ObjectNode env1 = MAPPER.createObjectNode();
    ObjectNode err1 = env1.putObject("error");
    err1.put("status", "RESOURCE_EXHAUSTED");
    err1.put("code", 429);
    assertEquals(ProviderErrorKind.TRANSIENT, GeminiErrorMapper.mapSseErrorEnvelope(env1).kind());

    ObjectNode env2 = MAPPER.createObjectNode();
    env2.put("empty", true);
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, GeminiErrorMapper.mapSseErrorEnvelope(env2).kind());
  }

  /** 验证异常信息中的 API Key、Secret、Query 等敏感词被安全脱敏，不泄漏给调用方。 */
  @Test
  void sanitizesSensitiveInformationInErrorMessage() {
    String sensitivePayload =
        """
        {
          "error": {
            "code": 400,
            "message": "Key AIzaSySecret12345 is rejected at https://generativelanguage.googleapis.com?key=AIzaSySecret12345"
          }
        }
        """;
    ProviderException ex = GeminiErrorMapper.fromHttp(400, sensitivePayload);
    assertNotNull(ex.getMessage());
    assertFalse(ex.getMessage().contains("AIzaSySecret12345"));
    assertFalse(ex.getMessage().contains("key="));
  }
}
