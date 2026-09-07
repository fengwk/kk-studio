package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 校验 HeaderSanitizer 的严格最小白名单机制、敏感词遮蔽、未知标头丢弃与 CRLF 注入防护。
 *
 * <p>确保未知标头（如 X-Debug 回显凭据）绝对不会泄露到对外元数据或异常对象中。
 */
class HeaderSanitizerTest {

  @Test
  void sensitiveKeywordsAreDetected() {
    assertTrue(HeaderSanitizer.isSensitiveHeader("Authorization"));
    assertTrue(HeaderSanitizer.isSensitiveHeader("x-goog-api-key"));
    assertTrue(HeaderSanitizer.isSensitiveHeader("user-secret-token"));
    assertTrue(HeaderSanitizer.isSensitiveHeader("X-Auth-Credential"));
    assertTrue(HeaderSanitizer.isSensitiveHeader("Cookie"));
    assertTrue(HeaderSanitizer.isSensitiveHeader("Set-Cookie"));
    assertTrue(HeaderSanitizer.isSensitiveHeader("API_KEY"));

    assertFalse(HeaderSanitizer.isSensitiveHeader("Content-Type"));
    assertFalse(HeaderSanitizer.isSensitiveHeader("x-request-id"));
    assertFalse(HeaderSanitizer.isSensitiveHeader(null));
    assertFalse(HeaderSanitizer.isSensitiveHeader(""));
  }

  @Test
  void unknownHeadersAreDroppedAndAllowlistPreservedWithoutCrlf() {
    Map<String, List<String>> raw =
        Map.of(
            "X-Request-Id", List.of(" req-12345 \r\n"),
            "Content-Type", List.of("text/event-stream\r\n"),
            "Retry-After", List.of("60"),
            "X-Debug-Session", List.of("confidential-debug-token"),
            "Server", List.of("cloudflare"),
            "Set-Cookie", List.of("sess=super-secret"));

    Map<String, List<String>> clean = HeaderSanitizer.sanitizeHeaders(raw);

    // 白名单内标头保留并规范化（去除前后空白与 CRLF）
    assertTrue(clean.containsKey("x-request-id"));
    assertEquals("req-12345", clean.get("x-request-id").get(0));
    assertTrue(clean.containsKey("content-type"));
    assertEquals("text/event-stream", clean.get("content-type").get(0));
    assertTrue(clean.containsKey("retry-after"));
    assertEquals("60", clean.get("retry-after").get(0));

    // 未知标头与 Cookie 等非协议诊断标头无条件丢弃
    assertFalse(clean.containsKey("x-debug-session"));
    assertFalse(clean.containsKey("server"));
    assertFalse(clean.containsKey("set-cookie"));
    assertNull(clean.get("x-debug-session"));
  }

  @Test
  void allowlistedHeaderWithSensitiveKeywordIsRedacted() {
    // 包含敏感关键词（token）的白名单标头（x-ratelimit-reset-tokens）必须被安全覆盖为 [REDACTED]
    Map<String, List<String>> raw =
        Map.of(
            "x-request-id", List.of("req-normal"),
            "x-ratelimit-reset-tokens", List.of("123456789"));

    Map<String, List<String>> clean = HeaderSanitizer.sanitizeHeaders(raw);
    assertEquals("req-normal", clean.get("x-request-id").get(0));
    assertEquals("[REDACTED]", clean.get("x-ratelimit-reset-tokens").get(0));
  }

  @Test
  void unknownHeaderCarryingSecretDoesNotLeakIntoExceptionOrMetadata() {
    String sensitiveSecret = "my-super-secret-credential-token-9988";
    Map<String, List<String>> raw =
        Map.of(
            "X-Debug", List.of(sensitiveSecret),
            "X-Upstream-Authorization-Echo", List.of(sensitiveSecret),
            "Content-Type", List.of("text/event-stream"),
            "X-Request-Id", List.of("req-safe-001"));

    // 1. 验证 HttpOpenMetadata 脱敏
    HttpOpenMetadata metadata = new HttpOpenMetadata(200, raw);
    assertFalse(metadata.headers().containsKey("x-debug"));
    assertFalse(metadata.headers().containsKey("x-upstream-authorization-echo"));
    assertFalse(metadata.toString().contains(sensitiveSecret));

    // 2. 验证 TransportException 脱敏
    Exception cause = new RuntimeException("Original nested internal failure");
    TransportException ex =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "HTTP 401 response received",
            401,
            "error body details".getBytes(),
            false,
            raw,
            cause);

    // safeHeaders 不含未知标头与 secret
    assertFalse(ex.safeHeaders().containsKey("x-debug"));
    assertFalse(ex.safeHeaders().containsKey("x-upstream-authorization-echo"));

    // getMessage(), toString(), getCause() 绝对不包含 secret 原始值
    assertFalse(ex.getMessage().contains(sensitiveSecret));
    assertFalse(ex.toString().contains(sensitiveSecret));
    assertFalse(ex.getCause().getMessage().contains(sensitiveSecret));
    assertFalse(ex.getCause().toString().contains(sensitiveSecret));
  }

  @Test
  void emptyOrNullHeadersReturnEmptyMap() {
    assertTrue(HeaderSanitizer.sanitizeHeaders(null).isEmpty());
    assertTrue(HeaderSanitizer.sanitizeHeaders(Map.of()).isEmpty());
  }
}
