package fun.fengwk.kkstudio.harness.provider.gemini;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Gemini 端点 URI 解析与安全规范化工具。 */
final class GeminiEndpoints {

  private GeminiEndpoints() {}

  /**
   * 将 ProviderDescriptor 的配置 baseUrl 校验并安全追加模型路径及 SSE 参数。
   *
   * <ul>
   *   <li>支持 http / https 协议；
   *   <li>必须包含合法 host；
   *   <li>严格禁止 user-info、query、fragment；
   *   <li>去除 baseUrl 尾部多余斜杠；
   *   <li>安全 URL path segment 编码模型名，追加 /models/{encodedModel}:streamGenerateContent?alt=sse；
   *   <li>严禁把 API Key 拼入 URL 中。
   * </ul>
   */
  static URI resolveStreamUri(String endpoint, String modelName) {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    URI baseUri = resolveBaseUri(endpoint);
    String rawPath = baseUri.getRawPath();
    if (rawPath == null) {
      rawPath = "";
    }
    while (rawPath.endsWith("/")) {
      rawPath = rawPath.substring(0, rawPath.length() - 1);
    }
    String encodedModel =
        URLEncoder.encode(modelName.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    String streamPath = rawPath + "/models/" + encodedModel + ":streamGenerateContent";
    String hostPort =
        baseUri.getPort() == -1 ? baseUri.getHost() : (baseUri.getHost() + ":" + baseUri.getPort());
    return URI.create(
        baseUri.getScheme().toLowerCase(Locale.ROOT) + "://" + hostPort + streamPath + "?alt=sse");
  }

  /** 将 ProviderDescriptor 的配置 baseUrl 进行基础合法性检查并规范化。 */
  static URI resolveBaseUri(String endpoint) {
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
    String hostPort = uri.getPort() == -1 ? uri.getHost() : (uri.getHost() + ":" + uri.getPort());
    return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + hostPort + rawPath);
  }
}
