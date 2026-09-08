package fun.fengwk.kkstudio.harness.provider.openai.responses;

import java.net.URI;
import java.util.Locale;

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
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("endpoint must not be blank");
    }
    URI uri;
    try {
      uri = URI.create(endpoint);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("endpoint is not a valid URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("endpoint scheme must be http or https");
    }
    if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
      throw new IllegalArgumentException("endpoint must not contain user-info");
    }
    if (uri.getQuery() != null || uri.getRawQuery() != null) {
      throw new IllegalArgumentException("endpoint must not contain query");
    }
    if (uri.getFragment() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException("endpoint must not contain fragment");
    }
    String rawAuthority = uri.getRawAuthority();
    if (rawAuthority == null
        || rawAuthority.isBlank()
        || uri.getHost() == null
        || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("endpoint must contain a valid host");
    }
    String rawPath = uri.getRawPath();
    if (rawPath == null) {
      rawPath = "";
    }
    while (rawPath.endsWith("/")) {
      rawPath = rawPath.substring(0, rawPath.length() - 1);
    }
    String responsesPath = rawPath + "/responses";
    try {
      return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + rawAuthority + responsesPath);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("endpoint is not a valid URI");
    }
  }
}
