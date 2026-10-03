package fun.fengwk.kkstudio.platform.configsync;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.mcp.discovery.McpToolDiscovery;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.List;
import java.util.Objects;

/**
 * 配置同步的 MCP 工具发现准备。
 *
 * <p>生产装配用 {@code new McpToolDiscovery()}；发现是网络 I/O，必须在数据库写事务之外执行。失败不伪造工具结果：返回 {@code null}
 * 表示该 server 只保存配置并保持 {@code UNVERIFIED}。
 */
@Component
public class ConfigSyncMcpDiscovery {

  private final McpToolDiscovery discovery;

  @Autowired
  public ConfigSyncMcpDiscovery() {
    this(new McpToolDiscovery());
  }

  /** 测试 / 手动装配：注入受控 discovery。 */
  public ConfigSyncMcpDiscovery(McpToolDiscovery discovery) {
    this.discovery = Objects.requireNonNull(discovery, "discovery");
  }

  /** 发现失败返回 {@code null}，不向上抛出网络错误。 */
  public List<McpTool> discoverOrNull(McpServer server) {
    try {
      return discovery.discover(server);
    } catch (AiValidationException error) {
      return null;
    }
  }
}
