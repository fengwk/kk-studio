package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Local MCP 传输配置。
 *
 * @param command 执行命令列表
 * @param cwd 进程工作目录
 * @param env 环境变量映射
 */
public record LocalConnectionConfig(List<String> command, String cwd, Map<String, String> env)
    implements McpConnectionConfig {

  public LocalConnectionConfig {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(cwd, "cwd");
    command = List.copyOf(command);
    env = env == null ? Map.of() : Map.copyOf(env);
  }

  @Override
  public String toString() {
    return "LocalConnectionConfig[redacted]";
  }
}
