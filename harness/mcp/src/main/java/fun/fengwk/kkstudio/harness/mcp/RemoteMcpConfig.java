package fun.fengwk.kkstudio.harness.mcp;

import java.util.Map;

/**
 * 远端 Streamable HTTP MCP 连接参数。
 *
 * <p>不得在 toString、日志或错误中回显 headers、token 或完整 URL：URL 本身可能内嵌凭证。
 */
public record RemoteMcpConfig(String url, Map<String, String> headers) {

  public RemoteMcpConfig {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("url must not be blank");
    }
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  /** toString 只暴露 URL 长度：URL 可能内嵌凭证，只有显式调用 {@link #url()} 才读取完整值。 */
  @Override
  public String toString() {
    return "RemoteMcpConfig[urlLength=" + url.length() + ", headersCount=" + headers.size() + "]";
  }
}
