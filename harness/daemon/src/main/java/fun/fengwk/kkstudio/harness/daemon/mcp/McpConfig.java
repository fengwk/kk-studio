package fun.fengwk.kkstudio.harness.daemon.mcp;

import java.util.List;
import java.util.Objects;

/** Daemon 本地 MCP 配置：全部配置的 server，保持文件顺序。 */
public record McpConfig(List<McpServerConfig> servers) {

  public McpConfig {
    servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
    long unique = servers.stream().map(McpServerConfig::name).distinct().count();
    if (unique != servers.size()) {
      throw new IllegalArgumentException("duplicate MCP server name");
    }
  }

  /** 未配置任何 MCP server。 */
  public static McpConfig empty() {
    return new McpConfig(List.of());
  }
}
