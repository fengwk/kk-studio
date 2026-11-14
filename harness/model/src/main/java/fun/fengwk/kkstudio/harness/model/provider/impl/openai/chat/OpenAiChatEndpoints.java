package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiEndpointUri;

import java.net.URI;

/** OpenAI Chat Completions 端点 URI 解析与安全规范化工具。 */
final class OpenAiChatEndpoints {

  private OpenAiChatEndpoints() {}

  /**
   * 将 ProviderDescriptor 的配置 baseUrl 校验并规范化为最终的 chat completions 端点 URI。
   *
   * <ul>
   *   <li>支持 http / https 协议；
   *   <li>必须包含合法 host；
   *   <li>严格禁止 user-info、query、fragment；
   *   <li>去除 baseUrl 尾部多余斜杠后追加 /chat/completions。
   * </ul>
   */
  static URI resolveChatCompletionsUri(String endpoint) {
    URI uri = OpenAiEndpointUri.parse(endpoint);
    String scheme = OpenAiEndpointUri.requireHttpScheme(uri);
    String rawAuthority = OpenAiEndpointUri.requireAuthority(uri);
    OpenAiEndpointUri.requireNoUserInfo(uri, true);
    OpenAiEndpointUri.requireNoQuery(uri);
    OpenAiEndpointUri.requireNoFragment(uri);
    OpenAiEndpointUri.requireHost(uri);
    return OpenAiEndpointUri.rebuild(
        scheme, rawAuthority, OpenAiEndpointUri.appendPath(uri, "/chat/completions"));
  }
}
