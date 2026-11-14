package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiEndpointUri;

import java.net.URI;

/** OpenAI Responses 端点 URI 解析与安全规范化工具。 */
final class OpenAiResponsesEndpoints {

  private OpenAiResponsesEndpoints() {}

  /**
   * 将 ProviderDescriptor 的 baseUrl 校验并规范化为最终的 responses 端点 URI。
   *
   * <ul>
   *   <li>支持 http / https 协议；
   *   <li>必须包含合法 host 与 authority；
   *   <li>严格禁止 user-info、query、fragment；
   *   <li>保留 raw authority、IPv6、端口和已转义 base path，规范去掉尾斜杠并追加 /responses；
   *   <li>异常不得回显 endpoint 或任何凭证敏感信息。
   * </ul>
   */
  static URI resolveResponsesUri(String endpoint) {
    URI uri = OpenAiEndpointUri.parse(endpoint);
    String scheme = OpenAiEndpointUri.requireHttpScheme(uri);
    OpenAiEndpointUri.requireNoUserInfo(uri, false);
    OpenAiEndpointUri.requireNoQuery(uri);
    OpenAiEndpointUri.requireNoFragment(uri);
    String rawAuthority = OpenAiEndpointUri.requireAuthority(uri);
    OpenAiEndpointUri.requireHost(uri);
    return OpenAiEndpointUri.rebuild(
        scheme, rawAuthority, OpenAiEndpointUri.appendPath(uri, "/responses"));
  }
}
