package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.ProviderErrorHelper;
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

/** Gemini 错误码分类与所见即所得真实错误正文暴露测试。 */
class GeminiErrorMapperTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 验证 HTTP 400 及 INVALID_ARGUMENT 规范映射为 INVALID_REQUEST 且完整保留正文。 */
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
    assertEquals("HTTP 400\n" + errorJson, ex.getMessage());
  }

  /** 验证 HTTP 401 及 403 映射为认证失败且完整保留正文。 */
  @Test
  void maps401And403AuthErrors() {
    String unauth =
        "{\"error\":{\"code\":401,\"message\":\"API key not valid\",\"status\":\"UNAUTHENTICATED\"}}";
    ProviderException ex1 = GeminiErrorMapper.fromHttp(401, unauth);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex1.kind());
    assertEquals("HTTP 401\n" + unauth, ex1.getMessage());

    String denied =
        "{\"error\":{\"code\":403,\"message\":\"Quota exceeded\",\"status\":\"PERMISSION_DENIED\"}}";
    ProviderException ex2 = GeminiErrorMapper.fromHttp(403, denied);
    assertEquals(ProviderErrorKind.AUTHENTICATION, ex2.kind());
    assertEquals("HTTP 403\n" + denied, ex2.getMessage());
  }

  /** 验证 HTTP 402 映射为 BILLING 且保留正文。 */
  @Test
  void maps402BillingError() {
    ProviderException ex = GeminiErrorMapper.fromHttp(402, "{}");
    assertEquals(ProviderErrorKind.BILLING, ex.kind());
    assertEquals("HTTP 402\n{}", ex.getMessage());
  }

  /** 验证 HTTP 404 映射为 INVALID_REQUEST 且保留正文。 */
  @Test
  void maps404NotFound() {
    String notFound =
        "{\"error\":{\"code\":404,\"message\":\"models/gemini-unknown not found\",\"status\":\"NOT_FOUND\"}}";
    ProviderException ex = GeminiErrorMapper.fromHttp(404, notFound);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals("HTTP 404\n" + notFound, ex.getMessage());
  }

  /** 验证 HTTP 429 映射为 TRANSIENT 且保留正文。 */
  @Test
  void maps429ResourceExhausted() {
    String rateLimit =
        "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
    ProviderException ex = GeminiErrorMapper.fromHttp(429, rateLimit);
    assertEquals(ProviderErrorKind.TRANSIENT, ex.kind());
    assertEquals("HTTP 429\n" + rateLimit, ex.getMessage());
  }

  /** 验证 HTTP 500/503 映射为 TRANSIENT 且保留正文。 */
  @Test
  void maps500And503ServerErrors() {
    String internalJson = "{\"error\":{\"status\":\"INTERNAL\"}}";
    ProviderException ex1 = GeminiErrorMapper.fromHttp(500, internalJson);
    assertEquals(ProviderErrorKind.TRANSIENT, ex1.kind());
    assertEquals("HTTP 500\n" + internalJson, ex1.getMessage());

    String unavailJson = "{\"error\":{\"status\":\"UNAVAILABLE\"}}";
    ProviderException ex2 = GeminiErrorMapper.fromHttp(503, unavailJson);
    assertEquals(ProviderErrorKind.TRANSIENT, ex2.kind());
    assertEquals("HTTP 503\n" + unavailJson, ex2.getMessage());
  }

  /** 验证底层网络与超时异常的规范映射。 */
  @Test
  void mapsTransportExceptions() {
    ProviderException timeoutEx =
        GeminiErrorMapper.fromThrowable(new TimeoutException("read timed out"));
    assertEquals(ProviderErrorKind.TRANSIENT, timeoutEx.kind());
    assertEquals("Gemini request timed out", timeoutEx.getMessage());

    ProviderException sockTimeoutEx =
        GeminiErrorMapper.fromThrowable(new SocketTimeoutException("connect timed out"));
    assertEquals(ProviderErrorKind.TRANSIENT, sockTimeoutEx.kind());
    assertEquals("Gemini request timed out", sockTimeoutEx.getMessage());

    ProviderException connEx =
        GeminiErrorMapper.fromThrowable(new ConnectException("connection refused"));
    assertEquals(ProviderErrorKind.TRANSIENT, connEx.kind());
    assertEquals("Gemini network error", connEx.getMessage());

    ProviderException ioEx = GeminiErrorMapper.fromThrowable(new IOException("broken pipe"));
    assertEquals(ProviderErrorKind.TRANSIENT, ioEx.kind());
    assertEquals("Gemini network error", ioEx.getMessage());

    ProviderException cancelEx =
        GeminiErrorMapper.fromThrowable(new CancellationException("client canceled"));
    assertEquals(ProviderErrorKind.CANCELLED, cancelEx.kind());
    assertEquals("Gemini request cancelled", cancelEx.getMessage());

    ProviderException unknownEx =
        GeminiErrorMapper.fromThrowable(new IllegalArgumentException("runtime"));
    assertEquals(ProviderErrorKind.TRANSIENT, unknownEx.kind());
    assertEquals(GeminiErrorMapper.MSG_TRANSIENT, unknownEx.getMessage());
  }

  /** 验证 TransportException 各个 kind 的精确映射与静默取消。 */
  @Test
  void mapsTransportExceptionKinds() {
    ProviderException nullEx = GeminiErrorMapper.mapTransportException(null);
    assertNotNull(nullEx);
    assertEquals(ProviderErrorKind.TRANSIENT, nullEx.kind());
    assertEquals(GeminiErrorMapper.MSG_TRANSIENT, nullEx.getMessage());

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
    assertEquals("Gemini request timed out", timeoutEx.getMessage());

    ProviderException ioEx =
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.IO, "io"));
    assertEquals(ProviderErrorKind.TRANSIENT, ioEx.kind());
    assertEquals("Gemini I/O error", ioEx.getMessage());

    ProviderException invalidRespEx =
        GeminiErrorMapper.mapTransportException(
            new TransportException(TransportErrorKind.INVALID_RESPONSE, "invalid"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, invalidRespEx.kind());
    assertEquals(GeminiErrorMapper.MSG_INVALID_RESPONSE, invalidRespEx.getMessage());

    TransportException httpEx =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "server error",
            500,
            "{\"error\":{\"status\":\"INTERNAL\"}}".getBytes(StandardCharsets.UTF_8),
            Map.of());
    ProviderException peHttp = GeminiErrorMapper.mapTransportException(httpEx);
    assertEquals(ProviderErrorKind.TRANSIENT, peHttp.kind());
    assertEquals("HTTP 500\n{\"error\":{\"status\":\"INTERNAL\"}}", peHttp.getMessage());

    // fromThrowable wrapping TransportException
    ProviderException wrapped = GeminiErrorMapper.fromThrowable(httpEx);
    assertEquals(ProviderErrorKind.TRANSIENT, wrapped.kind());
    assertEquals("HTTP 500\n{\"error\":{\"status\":\"INTERNAL\"}}", wrapped.getMessage());
  }

  /** 验证 SSE Error Envelope 的映射解析与边界情况。 */
  @Test
  void mapsSseErrorEnvelope() {
    ProviderException peNull = GeminiErrorMapper.mapSseErrorEnvelope(null);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, peNull.kind());
    assertEquals(GeminiErrorMapper.MSG_INVALID_RESPONSE, peNull.getMessage());

    ProviderException peInt = GeminiErrorMapper.mapSseErrorEnvelope(new IntNode(123));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, peInt.kind());
    assertEquals("123", peInt.getMessage());

    ObjectNode env1 = MAPPER.createObjectNode();
    ObjectNode err1 = env1.putObject("error");
    err1.put("status", "RESOURCE_EXHAUSTED");
    err1.put("code", 429);
    err1.put("details", "quota exhausted");
    ProviderException peEnv1 = GeminiErrorMapper.mapSseErrorEnvelope(env1);
    assertEquals(ProviderErrorKind.TRANSIENT, peEnv1.kind());
    assertEquals(env1.toString(), peEnv1.getMessage());
    assertTrue(peEnv1.getMessage().contains("quota exhausted"));

    ObjectNode env2 = MAPPER.createObjectNode();
    env2.put("empty", true);
    ProviderException peEnv2 = GeminiErrorMapper.mapSseErrorEnvelope(env2);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, peEnv2.kind());
    assertEquals(env2.toString(), peEnv2.getMessage());
  }

  /** 验证客户端所见即所得保留上游原始返回，且不向上游泄露内部 transport message 或底层 cause。 */
  @Test
  void retainsRawBodyAndGuardsTransportInternals() {
    String rawPayload =
        """
        {
          "error": {
            "code": 400,
            "message": "Key fake-gemini-key-123 is rejected at https://generativelanguage.googleapis.com?key=fake-gemini-key-123"
          }
        }
        """;
    ProviderException ex = GeminiErrorMapper.fromHttp(400, rawPayload);
    assertNotNull(ex.getMessage());
    assertEquals("HTTP 400\n" + rawPayload, ex.getMessage());
    assertTrue(ex.getMessage().contains("fake-gemini-key-123"));

    TransportException tex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "internal transport failure with key fake-gemini-key-123",
            400,
            rawPayload.getBytes(StandardCharsets.UTF_8),
            false,
            null,
            new RuntimeException("secret underlying transport cause"));
    ProviderException mapped = GeminiErrorMapper.mapTransportException(tex);
    assertEquals("HTTP 400\n" + rawPayload, mapped.getMessage());
    assertFalse(mapped.getMessage().contains("internal transport failure"));
    assertNull(mapped.getCause());
  }

  @Test
  @DisplayName("验证正文缺失后备、空白、非 JSON、截断标记与多行 Unicode 正文")
  void testBodyVariantsAndTruncation() {
    // 无正文时带状态码回退
    ProviderException exNoBody401 = GeminiErrorMapper.fromHttp(401, null);
    assertEquals(ProviderErrorKind.AUTHENTICATION, exNoBody401.kind());
    assertEquals("HTTP 401: " + GeminiErrorMapper.MSG_AUTH, exNoBody401.getMessage());

    ProviderException exNoBody402 = GeminiErrorMapper.fromHttp(402, "");
    assertEquals(ProviderErrorKind.BILLING, exNoBody402.kind());
    assertEquals("HTTP 402: " + GeminiErrorMapper.MSG_BILLING, exNoBody402.getMessage());

    ProviderException exNoBody400 = GeminiErrorMapper.fromHttp(400, null);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exNoBody400.kind());
    assertEquals("HTTP 400: " + GeminiErrorMapper.MSG_INVALID_REQUEST, exNoBody400.getMessage());

    ProviderException exNoBody500 = GeminiErrorMapper.fromHttp(500, null);
    assertEquals(ProviderErrorKind.TRANSIENT, exNoBody500.kind());
    assertEquals("HTTP 500: " + GeminiErrorMapper.MSG_TRANSIENT, exNoBody500.getMessage());

    // 空白正文必须完整保留
    String whitespace = "   \n\t  ";
    ProviderException exWhitespace = GeminiErrorMapper.fromHttp(400, whitespace);
    assertEquals("HTTP 400\n" + whitespace, exWhitespace.getMessage());

    // 非 JSON 正文
    String html = "<html><body>Bad Gateway</body></html>";
    ProviderException exHtml = GeminiErrorMapper.fromHttp(502, html);
    assertEquals(ProviderErrorKind.TRANSIENT, exHtml.kind());
    assertEquals("HTTP 502\n" + html, exHtml.getMessage());

    // 多行 Unicode 正文
    String unicodeBody = "Gemini 异常：\n状态：RESOURCE_EXHAUSTED \uD83D\uDCA5\n建议稍后重试";
    ProviderException exUnicode =
        GeminiErrorMapper.mapTransportException(
            new TransportException(
                TransportErrorKind.HTTP_STATUS,
                "err",
                429,
                unicodeBody.getBytes(StandardCharsets.UTF_8),
                null));
    assertEquals(ProviderErrorKind.TRANSIENT, exUnicode.kind());
    assertEquals("HTTP 429\n" + unicodeBody, exUnicode.getMessage());

    // 截断正文标记
    byte[] truncatedBytes = "partial gemini error...".getBytes(StandardCharsets.UTF_8);
    TransportException exTruncated =
        new TransportException(
            TransportErrorKind.HTTP_STATUS, "err", 400, truncatedBytes, true, null, null);
    ProviderException peTruncated = GeminiErrorMapper.mapTransportException(exTruncated);
    assertEquals(
        "HTTP 400\npartial gemini error..." + ProviderErrorHelper.TRUNCATION_MARKER,
        peTruncated.getMessage());
  }
}
