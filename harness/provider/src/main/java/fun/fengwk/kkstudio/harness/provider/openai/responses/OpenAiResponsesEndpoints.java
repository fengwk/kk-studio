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
   *   <li>必须包含合法 host；
   *   <li>严格禁止 user-info、query、fragment；
   *   <li>去除 baseUrl 尾部多余斜杠后安全追加 /responses（兼容已含 /v1 或其他前缀路径）。
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
    if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException("endpoint must not contain user-info");
    }
    if (uri.getRawQuery() != null) {
      throw new IllegalArgumentException("endpoint must not contain query");
    }
    if (uri.getRawFragment() != null) {
      throw new IllegalArgumentException("endpoint must not contain fragment");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
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
    String hostPort = uri.getPort() == -1 ? uri.getHost() : (uri.getHost() + ":" + uri.getPort());
    return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + hostPort + responsesPath);
  }
}
