package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 单次 MCP 连接配置（仅 Streamable HTTP）。
 *
 * <p>字段即 {@code mcp_server} 行配置：URL、可空 Bearer token（非空时以 {@code Authorization: Bearer} header
 * 发送）与共用毫秒超时。该对象是敏感配置持有者：{@code toString} 与错误信息绝不包含 token 或 URL。
 */
public final class McpConnectionSpec {

  private final String url;
  private final String bearerToken;
  private final long timeoutMillis;

  public McpConnectionSpec(String url, String bearerToken, long timeoutMillis) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("url must not be blank");
    }
    validateStreamableHttpUrl(url);
    if (timeoutMillis <= 0) {
      throw new IllegalArgumentException("timeoutMillis must be positive");
    }
    this.url = url;
    this.bearerToken = bearerToken == null || bearerToken.isBlank() ? null : bearerToken;
    this.timeoutMillis = timeoutMillis;
  }

  private static void validateStreamableHttpUrl(String url) {
    try {
      URI uri = new URI(url);
      if (!uri.isAbsolute()) {
        throw new IllegalArgumentException("url must be an absolute http or https URL");
      }
      String scheme = uri.getScheme();
      if (scheme == null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        throw new IllegalArgumentException("url scheme must be http or https");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new IllegalArgumentException("url must contain a valid host");
      }
      if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
        throw new IllegalArgumentException("url must not contain user-info");
      }
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("url format is invalid");
    }
  }

  public String url() {
    return url;
  }

  public String bearerToken() {
    return bearerToken;
  }

  public long timeoutMillis() {
    return timeoutMillis;
  }

  @Override
  public String toString() {
    // 敏感配置绝不进入日志或错误信息。
    return "McpConnectionSpec[timeoutMillis=" + timeoutMillis + "]";
  }
}
