package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  @DisplayName("保留 IPv6 主机与端口")
  void resolveIpv6EndpointWithPort() {
    // 测试意图：确保 IPv6 地址的方括号保留且端口号拼接正确，不被拆散为主机丢失格式
    URI uri1 = OpenAiChatEndpoints.resolveChatCompletionsUri("http://[::1]:8080/v1");
    assertEquals("http://[::1]:8080/v1/chat/completions", uri1.toString());

    URI uri2 = OpenAiChatEndpoints.resolveChatCompletionsUri("https://[2001:db8::1]/v1///");
    assertEquals("https://[2001:db8::1]/v1/chat/completions", uri2.toString());
  }

  @Test
  @DisplayName("保留已转义的 base path，不发生二次转义")
  void preserveEscapedBasePath() {
    // 测试意图：验证 baseUrl 中已有百分号编码的路径能够原样保留，不会被解码改变路径语义或被双重编码为 %25
    URI uri = OpenAiChatEndpoints.resolveChatCompletionsUri("https://api.openai.com/v1%20custom/");
    assertEquals("https://api.openai.com/v1%20custom/chat/completions", uri.toString());
  }

  @Test
  @DisplayName("异常消息严格禁止回显 endpoint 或 credential")
  void errorMessagesDoNotLeakCredentialsOrEndpoint() {
    // 测试意图：安全加固验证，当端点包含敏感 credential、user-info 或非法路径时，抛出的异常信息绝不回显输入内容
    String secretEndpoint =
        "https://sensitiveUser:verySecretPassword@api.openai.com/v1?token=sensitiveToken";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiChatEndpoints.resolveChatCompletionsUri(secretEndpoint));
    String msg = ex.getMessage();
    assertFalse(msg.contains("sensitiveUser"));
    assertFalse(msg.contains("verySecretPassword"));
    assertFalse(msg.contains("sensitiveToken"));
    assertFalse(msg.contains(secretEndpoint));
  }

  @Test
  @DisplayName("私有构造器反射覆盖")
  void testPrivateConstructor() throws Exception {
    var ctor = OpenAiChatEndpoints.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertNotNull(ctor.newInstance());
  }
}
