package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;

/** MCP server 领域模型到公开 DTO 的转换（绝不回显敏感传输配置）。 */
public final class McpServerConverter {

  private McpServerConverter() {}

  public static McpServerDTO convert(McpServer server) {
    return convert(server, 0);
  }

  public static McpServerDTO convert(McpServer server, int toolCount) {
    if (server == null) {
      return null;
    }
    McpServerDTO dto = new McpServerDTO();
    dto.setId(server.getId().toString());
    dto.setName(server.getName());
    dto.setType(
        server.getConnectionType() == null ? null : server.getConnectionType().toExternal());
    dto.setEnvironmentId(
        server.getEnvironmentId() == null ? null : server.getEnvironmentId().toString());
    dto.setEnabled(server.isEnabled());
    dto.setTimeoutMillis(server.getTimeoutMillis());
    dto.setDiscoveryStatus(
        server.getDiscoveryStatus() == null ? null : server.getDiscoveryStatus().name());
    dto.setDiscoveredVersion(
        server.getDiscoveredVersion() == null
            ? null
            : CatalogVersions.format(server.getDiscoveredVersion()));
    dto.setToolCount(toolCount);
    dto.setVersion(CatalogVersions.format(server.getVersion()));
    dto.setCreateTime(server.getCreateTime());
    dto.setUpdateTime(server.getUpdateTime());
    return dto;
  }
}
