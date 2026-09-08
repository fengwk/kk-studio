package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URI;

/** 验证 OpenAI Responses 端点 URI 解析、规范化与安全防御规则。 */
class OpenAiResponsesEndpointsTest {

  /** 验证合法 endpoint 安全追加 /responses，支持已有路径前缀与尾斜杠兼容。 */
  @Test
  void test_resolveResponsesUri_success() {
    assertEquals(
        URI.create("https://api.openai.com/v1/responses"),
        OpenAiResponsesEndpoints.resolveResponsesUri("https://api.openai.com/v1"));
    assertEquals(
        URI.create("https://api.openai.com/v1/responses"),
        OpenAiResponsesEndpoints.resolveResponsesUri("https://api.openai.com/v1/"));
    assertEquals(
        URI.create("https://api.openai.com/responses"),
        OpenAiResponsesEndpoints.resolveResponsesUri("https://api.openai.com"));
    assertEquals(
        URI.create("https://api.openai.com/responses"),
        OpenAiResponsesEndpoints.resolveResponsesUri("https://api.openai.com/"));
    assertEquals(
        URI.create("http://localhost:8080/custom/prefix/responses"),
        OpenAiResponsesEndpoints.resolveResponsesUri("http://localhost:8080/custom/prefix"));
    assertEquals(
        URI.create("http://localhost:8080/custom/prefix/responses"),
        OpenAiResponsesEndpoints.resolveResponsesUri("http://localhost:8080/custom/prefix/"));
  }

  /** 验证对 null、空串、纯空白、非法 URI 格式的防御性拦截。 */
  @Test
  void test_resolveResponsesUri_blankOrMalformed() {
    assertThrows(
        IllegalArgumentException.class, () -> OpenAiResponsesEndpoints.resolveResponsesUri(null));
    assertThrows(
        IllegalArgumentException.class, () -> OpenAiResponsesEndpoints.resolveResponsesUri(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("   \t\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("://missing-scheme"));
  }

  /** 验证严格要求 http/https 协议并拒绝非合法 scheme。 */
  @Test
  void test_resolveResponsesUri_schemeValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("ftp://api.openai.com"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("file:///tmp/responses"));
  }

  /** 验证严格拒绝包含 user-info、query、fragment 以及缺失 host 的端点。 */
  @Test
  void test_resolveResponsesUri_securityGuards() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("https://user:pass@api.openai.com/v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("https://api.openai.com/v1?query=1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("https://api.openai.com/v1#frag"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiResponsesEndpoints.resolveResponsesUri("https:///v1"));
  }
}
