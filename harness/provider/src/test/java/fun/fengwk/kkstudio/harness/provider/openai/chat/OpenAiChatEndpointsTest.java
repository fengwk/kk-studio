package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

/**
 * 测试意图：验证 OpenAI Chat Completions 端点 URI 解析的安全性与规范化行为。 确保协议仅支持 http/https，严格拒绝
 * user-info、query、fragment，并安全消除尾部斜杠追加 /chat/completions。
 */
class OpenAiChatEndpointsTest {

  @Test
  @DisplayName("安全规范化标准 https baseUrl")
  void resolveStandardHttpsEndpoint() {
    URI uri = OpenAiChatEndpoints.resolveChatCompletionsUri("https://api.openai.com/v1");
    assertEquals("https://api.openai.com/v1/chat/completions", uri.toString());
  }

  @Test
  @DisplayName("自动去除 baseUrl 尾部的多个斜杠")
  void resolveEndpointWithTrailingSlashes() {
    URI uri = OpenAiChatEndpoints.resolveChatCompletionsUri("https://api.openai.com/v1///");
    assertEquals("https://api.openai.com/v1/chat/completions", uri.toString());
  }

  @Test
  @DisplayName("支持携带端口号的 http baseUrl")
  void resolveHttpEndpointWithPort() {
    URI uri = OpenAiChatEndpoints.resolveChatCompletionsUri("http://localhost:8080");
    assertEquals("http://localhost:8080/chat/completions", uri.toString());
  }

  @Test
  @DisplayName("空字符串或空白字符串抛出 IllegalArgumentException")
  void rejectBlankEndpoint() {
    assertThrows(
        IllegalArgumentException.class, () -> OpenAiChatEndpoints.resolveChatCompletionsUri(null));
    assertThrows(
        IllegalArgumentException.class, () -> OpenAiChatEndpoints.resolveChatCompletionsUri("   "));
  }

  @Test
  @DisplayName("拒绝非法 scheme")
  void rejectInvalidScheme() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiChatEndpoints.resolveChatCompletionsUri("ftp://api.openai.com"));
  }

  @Test
  @DisplayName("拒绝包含 user-info 的 endpoint")
  void rejectUserInfo() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiChatEndpoints.resolveChatCompletionsUri("https://user:pass@api.openai.com/v1"));
  }

  @Test
  @DisplayName("拒绝包含 query 参数的 endpoint")
  void rejectQuery() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiChatEndpoints.resolveChatCompletionsUri("https://api.openai.com/v1?foo=bar"));
  }

  @Test
  @DisplayName("拒绝包含 fragment 的 endpoint")
  void rejectFragment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiChatEndpoints.resolveChatCompletionsUri("https://api.openai.com/v1#hash"));
  }

  @Test
  @DisplayName("拒绝无 host 的 endpoint")
  void rejectNoHost() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiChatEndpoints.resolveChatCompletionsUri("http:///path"));
  }

  @Test
  @DisplayName("拒绝语法非法的 URI")
  void rejectMalformedUri() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenAiChatEndpoints.resolveChatCompletionsUri("http://[invalid-ipv6"));
  }

  @Test
  @DisplayName("去除多余连续尾部斜杠")
  void trimMultipleTrailingSlashes() {
    URI uri = OpenAiChatEndpoints.resolveChatCompletionsUri("https://api.openai.com/v1///");
    assertEquals("https://api.openai.com/v1/chat/completions", uri.toString());
  }

  @Test
  @DisplayName("私有构造器反射覆盖")
  void testPrivateConstructor() throws Exception {
    var ctor = OpenAiChatEndpoints.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertNotNull(ctor.newInstance());
  }
}
