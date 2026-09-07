package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** 校验 HeaderSanitizer 的敏感词拦截与 CRLF 清洗规则。 */
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
    assertFalse(HeaderSanitizer.isSensitiveHeader("Accept"));
    assertFalse(HeaderSanitizer.isSensitiveHeader(null));
    assertFalse(HeaderSanitizer.isSensitiveHeader(""));
  }

  @Test
  void sanitizeHeadersRedactsSensitiveValuesAndRemovesCrlf() {
    Map<String, List<String>> raw =
        Map.of(
            "x-goog-api-key", List.of("AIzaSyMyKey"),
            "Safe-Header", List.of("Line1\r\nLine2"),
            "Custom-Token", List.of("SecretToken"));

    Map<String, List<String>> clean = HeaderSanitizer.sanitizeHeaders(raw);

    assertEquals("[REDACTED]", clean.get("x-goog-api-key").get(0));
    assertEquals("[REDACTED]", clean.get("Custom-Token").get(0));
    assertEquals("Line1Line2", clean.get("Safe-Header").get(0));
  }

  @Test
  void emptyOrNullHeadersReturnEmptyMap() {
    assertTrue(HeaderSanitizer.sanitizeHeaders(null).isEmpty());
    assertTrue(HeaderSanitizer.sanitizeHeaders(Map.of()).isEmpty());
  }
}
