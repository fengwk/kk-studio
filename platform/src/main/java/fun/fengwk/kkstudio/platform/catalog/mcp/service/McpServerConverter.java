package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;

/** MCP server 领域模型到公开 DTO 的转换（绝不回显 bearer token）。 */
public final class McpServerConverter {

  private McpServerConverter() {}

  public static McpServerDTO convert(McpServer server) {
    if (server == null) {
      return null;
    }
    McpServerDTO dto = new McpServerDTO();
    dto.setId(server.getId().toString());
    dto.setName(server.getName());
    dto.setUrl(server.getUrl());
    dto.setBearerTokenConfigured(
        server.getBearerToken() != null && !server.getBearerToken().isBlank());
    dto.setTimeoutMillis(server.getTimeoutMillis());
    dto.setVersion(CatalogVersions.format(server.getVersion()));
    dto.setCreateTime(server.getCreateTime());
    dto.setUpdateTime(server.getUpdateTime());
    return dto;
  }
}
