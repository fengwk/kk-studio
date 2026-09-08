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

  /** 验证保留已验证 endpoint 的 raw authority（包含 IPv6 和端口）。 */
  @Test
  void resolveBaseUri_preservesRawAuthorityAndIpv6() {
    URI uriIpv6 = GeminiEndpoints.resolveBaseUri("http://[::1]:8080/v1beta/");
    assertEquals("http://[::1]:8080/v1beta", uriIpv6.toString());

    URI uriPort = GeminiEndpoints.resolveBaseUri("https://example.com:8443/api");
    assertEquals("https://example.com:8443/api", uriPort.toString());

    URI uriStream =
        GeminiEndpoints.resolveStreamUri("http://[::1]:8080/v1beta", "gemini-2.5-flash");
    assertEquals(
        "http://[::1]:8080/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
        uriStream.toString());
  }

  /** 验证保留已转义 raw base path，避免二次转义（如 %20 不会被再次编码为 %2520）。 */
  @Test
  void resolveBaseUri_preservesRawEscapedBasePath_avoidsDoubleEncoding() {
    URI baseUri = GeminiEndpoints.resolveBaseUri("https://example.com/api%20v1//");
    assertEquals("https://example.com/api%20v1", baseUri.toString());

    URI streamUri = GeminiEndpoints.resolveStreamUri("https://example.com/api%20v1/", "gemini-1.5");
    assertEquals(
        "https://example.com/api%20v1/models/gemini-1.5:streamGenerateContent?alt=sse",
        streamUri.toString());
  }

  /** 验证空串、blank、非法 URI 格式抛出明确的 IllegalArgumentException，且异常信息脱敏不回显。 */
  @Test
  void resolveBaseUri_rejectsBlankOrInvalidUri_redactsErrorMessage() {
    IllegalArgumentException ex1 =
        assertThrows(IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri(null));
    assertEquals("endpoint must not be blank", ex1.getMessage());

    IllegalArgumentException ex2 =
        assertThrows(IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri("   "));
    assertEquals("endpoint must not be blank", ex2.getMessage());

    String secretEndpoint = ":::secret_payload:::";
    IllegalArgumentException ex3 =
        assertThrows(
            IllegalArgumentException.class, () -> GeminiEndpoints.resolveBaseUri(secretEndpoint));
    assertEquals("endpoint is not a valid URI", ex3.getMessage());
    assertFalse(ex3.getMessage().contains("secret_payload"));
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

  /** 验证模型名称按照 RFC 3986 path-segment 语义编码（仅 unreserved 保留，其余均 percent-encode），并追加 alt=sse。 */
  @Test
  void resolveStreamUri_rfc3986PathSegmentEncoding_appendsModelPathAndAltSse() {
    // 1. 标准名称，仅 unreserved 字符 (ALPHA / DIGIT / "-" / "." / "_" / "~")
    URI uri1 =
        GeminiEndpoints.resolveStreamUri(
            "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash_v1.0~rc");
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash_v1.0~rc:streamGenerateContent?alt=sse",
        uri1.toString());

    // 2. 包含斜杠、冒号、空格、星号、加号及 UTF-8 中文
    // 注意：RFC 3986 unreserved 不包含 '*' 和 '+'，必须 percent-encode
    URI uri2 =
        GeminiEndpoints.resolveStreamUri(
            "https://example.com/api", "tunedModels/my custom:model+v1*测试");
    assertEquals(
        "https://example.com/api/models/tunedModels%2Fmy%20custom%3Amodel%2Bv1%2A%E6%B5%8B%E8%AF%95:streamGenerateContent?alt=sse",
        uri2.toString());
  }

  /** 验证模型名称为空或 blank 时抛出异常，且脱敏不回显。 */
  @Test
  void resolveStreamUri_rejectsBlankModelName() {
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class,
            () -> GeminiEndpoints.resolveStreamUri("https://example.com", null));
    assertEquals("modelName must not be blank", ex1.getMessage());

    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class,
            () -> GeminiEndpoints.resolveStreamUri("https://example.com", "   "));
    assertEquals("modelName must not be blank", ex2.getMessage());
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
