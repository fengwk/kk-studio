package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import java.util.Map;
import java.util.Objects;

/**
 * Remote MCP 传输配置。
 *
 * @param url 远端 Streamable HTTP endpoint URL
 * @param headers 静态自定义 headers（未解析 ${VAR}）
 */
public record RemoteConnectionConfig(String url, Map<String, String> headers)
    implements McpConnectionConfig {

  public RemoteConnectionConfig {
    Objects.requireNonNull(url, "url");
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  @Override
  public String toString() {
    return "RemoteConnectionConfig[redacted]";
  }
}
