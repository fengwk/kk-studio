package fun.fengwk.kkstudio.harness.provider.anthropic;

import java.net.URI;
import java.util.Locale;

/** Anthropic 端点 URI 解析与安全规范化工具。 */
final class AnthropicEndpoints {

  private AnthropicEndpoints() {}

  /**
   * 将 ProviderDescriptor 的配置 baseUrl 校验并规范化为最终的 messages 端点 URI。
   *
   * <ul>
   *   <li>支持 http / https 协议；
   *   <li>必须包含合法 host 与 raw authority；
   *   <li>严格禁止 user-info、query、fragment；
   *   <li>保留 raw authority（包括 IPv6 方括号与端口）以及已编码的 base path；
   *   <li>去除 baseUrl 尾部多余斜杠后追加 /messages；
   *   <li>最终 URI 构造异常严格脱敏，不回显输入内容并不保留底层的异常原因。
   * </ul>
   */
  static URI resolveMessagesUri(String endpoint) {
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
    if (uri.getUserInfo() != null
        || uri.getRawUserInfo() != null
        || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
      throw new IllegalArgumentException("endpoint must not contain user-info");
    }
    if (uri.getRawQuery() != null || uri.getQuery() != null) {
      throw new IllegalArgumentException("endpoint must not contain query");
    }
    if (uri.getRawFragment() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("endpoint must not contain fragment");
    }
    if (uri.getHost() == null
        || uri.getHost().isBlank()
        || uri.getRawAuthority() == null
        || uri.getRawAuthority().isBlank()) {
      throw new IllegalArgumentException("endpoint must contain a valid host");
    }
    String rawPath = uri.getRawPath();
    if (rawPath == null) {
      rawPath = "";
    }
    while (rawPath.endsWith("/")) {
      rawPath = rawPath.substring(0, rawPath.length() - 1);
    }
    String messagesPath = rawPath + "/messages";
    String targetUri =
        scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + messagesPath;
    try {
      return URI.create(targetUri);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("endpoint is not a valid URI");
    }
  }
}
