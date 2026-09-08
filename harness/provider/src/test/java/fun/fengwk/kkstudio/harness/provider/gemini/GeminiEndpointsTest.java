package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URI;

/** Gemini 端点与 URL 解析规范化单元测试。 */
class GeminiEndpointsTest {

  /** 验证合法 http 与 https endpoint 能够正确规范化并剥离尾部多余斜杠。 */
  @Test
  void resolveBaseUri_validHttpAndHttps_stripsTrailingSlashes() {
    URI uri1 = GeminiEndpoints.resolveBaseUri("https://generativelanguage.googleapis.com/v1beta/");
    assertEquals("https://generativelanguage.googleapis.com/v1beta", uri1.toString());

    URI uri2 = GeminiEndpoints.resolveBaseUri("http://localhost:8080/gateway///");
    assertEquals("http://localhost:8080/gateway", uri2.toString());

    URI uri3 = GeminiEndpoints.resolveBaseUri("https://example.com");
    assertEquals("https://example.com", uri3.toString());
  }

  /** 验证空串、blank、非法 URI 格式抛出明确的 IllegalArgumentException。 */
  @Test
  void resolveBaseUri_rejectsBlankOrInvalidUri() {
    assertThrows(IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri(null));
    assertThrows(IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri("   "));
    assertThrows(
        IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri(":::not a uri"));
  }

  /** 验证 endpoint 包含非 http/https 协议被拒绝。 */
  @Test
  void resolveBaseUri_rejectsNonHttpSchemes() {
    assertThrows(
        IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri("ftp://example.com"));
    assertThrows(
        IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri("file:///tmp/test"));
  }

  /** 验证严格禁止在 endpoint 中携带 query 参数、fragment 或 user-info，防止凭据与配置注入。 */
  @Test
  void resolveBaseUri_rejectsUserInfoQueryAndFragment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> GeminiEndpoints.resolveBaseUri("https://user:pass@example.com/v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> GeminiEndpoints.resolveBaseUri("https://example.com/v1?key=secret"));
    assertThrows(
        IllegalArgumentException.class,
        () -> GeminiEndpoints.resolveBaseUri("https://example.com/v1#hash"));
  }

  /** 验证缺失 host 的 endpoint 被拒绝。 */
  @Test
  void resolveBaseUri_rejectsMissingHost() {
    assertThrows(
        IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri("https:///path"));
  }

  /** 验证模型名称被安全 URL 编码，并正确追加 /models/{model}:streamGenerateContent?alt=sse。 */
  @Test
  void resolveStreamUri_appendsModelPathAndAltSse_safelyEncodesModelName() {
    URI streamUri =
        GeminiEndpoints.resolveStreamUri(
            "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash");
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
        streamUri.toString());

    URI encodedUri =
        GeminiEndpoints.resolveStreamUri(
            "https://example.com/api", "tunedModels/my custom model:v1");
    assertEquals(
        "https://example.com/api/models/tunedModels%2Fmy%20custom%20model%3Av1:streamGenerateContent?alt=sse",
        encodedUri.toString());
  }

  /** 验证模型名称为空或 blank 时抛出异常。 */
  @Test
  void resolveStreamUri_rejectsBlankModelName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> GeminiEndpoints.resolveStreamUri("https://example.com", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> GeminiEndpoints.resolveStreamUri("https://example.com", "   "));
  }

  /** 验证无论输入如何，API Key 都绝不会被拼接到 URI 中。 */
  @Test
  void doesNotLeakApiKeyInUri() {
    String dummyKey = "AIzaSySecretKey12345";
    URI uri =
        GeminiEndpoints.resolveStreamUri(
            "https://generativelanguage.googleapis.com", "gemini-1.5-pro");
    assertFalse(uri.toString().contains(dummyKey));
    assertFalse(uri.toString().contains("key="));
  }
}
