package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URI;

/** 验证 Anthropic 端点规范化与安全性校验。 */
class AnthropicEndpointsTest {

  /** 意图：验证标准 baseUrl 正确追加 /messages。 */
  @Test
  void resolvesStandardEndpointWithoutTrailingSlash() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1");
    assertEquals("https://api.anthropic.com/v1/messages", uri.toString());
  }

  /** 意图：验证带尾部斜杠的标准 baseUrl 规范化后正确追加 /messages。 */
  @Test
  void resolvesStandardEndpointWithTrailingSlash() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1/");
    assertEquals("https://api.anthropic.com/v1/messages", uri.toString());
  }

  /** 意图：验证保留自定义端口。 */
  @Test
  void resolvesHttpEndpointWithCustomPort() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("http://127.0.0.1:8080");
    assertEquals("http://127.0.0.1:8080/v1/messages", uri.toString());

    URI httpsUri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com:9443/v1");
    assertEquals("https://api.anthropic.com:9443/v1/messages", httpsUri.toString());
  }

  /** 意图：验证原生 IPv6 authority（方括号及可选端口）保真保留。 */
  @Test
  void resolvesHttpEndpointWithIpv6HostAndPort() {
    URI uriWithPort = AnthropicEndpoints.resolveMessagesUri("http://[::1]:8443/v1");
    assertEquals("http://[::1]:8443/v1/messages", uriWithPort.toString());
    assertEquals("[::1]:8443", uriWithPort.getRawAuthority());

    URI uriWithoutPort = AnthropicEndpoints.resolveMessagesUri("https://[2001:db8::1]/v1/");
    assertEquals("https://[2001:db8::1]/v1/messages", uriWithoutPort.toString());
    assertEquals("[2001:db8::1]", uriWithoutPort.getRawAuthority());
  }

  /** 意图：验证已进行 URL 编码的 base path 保真，绝不进行二次编码。 */
  @Test
  void preservesAlreadyEscapedBasePathWithoutDoubleEncoding() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/custom%20path/v1");
    assertEquals("https://api.anthropic.com/custom%20path/v1/messages", uri.toString());
    assertEquals("/custom%20path/v1/messages", uri.getRawPath());
    assertFalse(uri.getRawPath().contains("%2520"));
  }

  /** 意图：验证空端点被安全拒绝且无 cause。 */
  @Test
  void rejectsBlankOrNullEndpoint() {
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri(null));
    assertNull(ex1.getCause());

    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri("   "));
    assertNull(ex2.getCause());
  }

  /** 意图：验证非法 scheme 被拒绝且无敏感信息泄露或底层 cause。 */
  @Test
  void rejectsInvalidScheme() {
    String sensitive = "ftp://sensitive-credential-endpoint";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri(sensitive));
    assertNull(ex.getCause());
    assertFalse(ex.getMessage().contains("sensitive-credential-endpoint"));
  }

  /** 意图：验证 user-info 被拒绝且密码等敏感凭据不回显，无底层 cause。 */
  @Test
  void rejectsUserInfo() {
    String sensitive = "https://user:super_secret_password@api.anthropic.com/v1";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri(sensitive));
    assertNull(ex.getCause());
    assertFalse(ex.getMessage().contains("super_secret_password"));
  }

  /** 意图：验证 query 参数被拒绝且查询串敏感数据不回显，无底层 cause。 */
  @Test
  void rejectsQuery() {
    String sensitive = "https://api.anthropic.com/v1?api_key=secret_token_123";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri(sensitive));
    assertNull(ex.getCause());
    assertFalse(ex.getMessage().contains("secret_token_123"));
  }

  /** 意图：验证 fragment 被拒绝且片段敏感数据不回显，无底层 cause。 */
  @Test
  void rejectsFragment() {
    String sensitive = "https://api.anthropic.com/v1#secret_section";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri(sensitive));
    assertNull(ex.getCause());
    assertFalse(ex.getMessage().contains("secret_section"));
  }

  /** 意图：验证非法语法端点被拒绝且异常脱敏，无底层 cause。 */
  @Test
  void rejectsMalformedUriSyntax() {
    String sensitive = "://invalid_secret_path";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri(sensitive));
    assertNull(ex.getCause());
    assertFalse(ex.getMessage().contains("invalid_secret_path"));
  }

  /** 意图：验证缺失合法 host 时被安全拒绝且无 cause。 */
  @Test
  void rejectsMissingHost() {
    IllegalArgumentException ex1 =
        assertThrows(
            IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri("http://"));
    assertNull(ex1.getCause());

    IllegalArgumentException ex2 =
        assertThrows(
            IllegalArgumentException.class,
            () -> AnthropicEndpoints.resolveMessagesUri("http:///path"));
    assertNull(ex2.getCause());
  }

  /** 意图：验证无 path 的域名能正确追加 /v1/messages。 */
  @Test
  void resolvesHostWithoutPath() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com");
    assertEquals("https://api.anthropic.com/v1/messages", uri.toString());

    URI uriTrailingSlash = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/");
    assertEquals("https://api.anthropic.com/v1/messages", uriTrailingSlash.toString());
  }

  /** 意图：验证已以 /v1/messages 结尾的完整 endpoint 保持不变且支持尾部斜杠规范化。 */
  @Test
  void resolvesFullV1MessagesEndpoint() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1/messages");
    assertEquals("https://api.anthropic.com/v1/messages", uri.toString());

    URI uriTrailing =
        AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1/messages/");
    assertEquals("https://api.anthropic.com/v1/messages", uriTrailing.toString());
  }

  /** 意图：验证已以 /messages 结尾的显式完整 endpoint 保持不变且支持尾部斜杠规范化。 */
  @Test
  void resolvesExplicitMessagesEndpoint() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/messages");
    assertEquals("https://api.anthropic.com/messages", uri.toString());

    URI uriTrailing = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/messages/");
    assertEquals("https://api.anthropic.com/messages", uriTrailing.toString());
  }

  /** 意图：验证自定义 proxy base 路径在各种结尾情况下的正确解析。 */
  @Test
  void resolvesCustomProxyBasePaths() {
    URI proxyBase = AnthropicEndpoints.resolveMessagesUri("https://proxy.example.com/anthropic");
    assertEquals("https://proxy.example.com/anthropic/v1/messages", proxyBase.toString());

    URI proxyBaseTrailing =
        AnthropicEndpoints.resolveMessagesUri("https://proxy.example.com/anthropic/");
    assertEquals("https://proxy.example.com/anthropic/v1/messages", proxyBaseTrailing.toString());

    URI proxyV1 = AnthropicEndpoints.resolveMessagesUri("https://proxy.example.com/anthropic/v1");
    assertEquals("https://proxy.example.com/anthropic/v1/messages", proxyV1.toString());

    URI proxyV1Messages =
        AnthropicEndpoints.resolveMessagesUri("https://proxy.example.com/anthropic/v1/messages");
    assertEquals("https://proxy.example.com/anthropic/v1/messages", proxyV1Messages.toString());

    URI proxyMessages =
        AnthropicEndpoints.resolveMessagesUri("https://proxy.example.com/anthropic/messages");
    assertEquals("https://proxy.example.com/anthropic/messages", proxyMessages.toString());
  }

  /** 意图：验证端点解析对所有合法形态均具备幂等性（二次解析结果完全相同）。 */
  @Test
  void resolvesIdempotentlyAcrossAllShapes() {
    String[] testCases = {
      "https://api.anthropic.com",
      "https://api.anthropic.com/",
      "https://api.anthropic.com/v1",
      "https://api.anthropic.com/v1/",
      "https://api.anthropic.com/v1/messages",
      "https://api.anthropic.com/v1/messages/",
      "https://api.anthropic.com/messages",
      "https://api.anthropic.com/messages/",
      "http://127.0.0.1:8080",
      "http://[::1]:8443/custom",
      "https://proxy.example.com/gateway/v1"
    };

    for (String testCase : testCases) {
      URI firstPass = AnthropicEndpoints.resolveMessagesUri(testCase);
      URI secondPass = AnthropicEndpoints.resolveMessagesUri(firstPass.toString());
      assertEquals(firstPass, secondPass, "resolveMessagesUri must be idempotent for: " + testCase);
    }
  }
}
