package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;

/** MCP server 领域模型到公开 DTO 的转换（绝不回显 URL 与 headers）。 */
public final class McpServerConverter {

  private McpServerConverter() {}

  public static McpServerDTO convert(McpServer server, int toolCount) {
    if (server == null) {
      return null;
    }
    McpServerDTO dto = new McpServerDTO();
    dto.setName(server.getName());
    dto.setEnabled(server.isEnabled());
    dto.setTimeoutMillis(server.getTimeoutMillis());
    dto.setDiscoveryStatus(
        server.getDiscoveryStatus() == null ? null : server.getDiscoveryStatus().name());
    dto.setToolCount(toolCount);
    dto.setVersion(CatalogVersions.format(server.getVersion()));
    dto.setCreateTime(server.getCreateTime());
    dto.setUpdateTime(server.getUpdateTime());
    return dto;
  }
}
