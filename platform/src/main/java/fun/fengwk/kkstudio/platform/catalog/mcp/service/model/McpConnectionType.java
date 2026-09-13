package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import fun.fengwk.kkstudio.platform.error.AiValidationException;

/** MCP Server 连接类型：REMOTE 或 LOCAL。 */
public enum McpConnectionType {
  REMOTE,
  LOCAL;

  public static McpConnectionType fromExternal(String val) {
    if (val == null || val.isBlank()) {
      throw new AiValidationException("mcp_server", "connection type must not be blank");
    }
    return switch (val.trim().toLowerCase()) {
      case "remote" -> REMOTE;
      case "local" -> LOCAL;
      default -> throw new AiValidationException(
          "mcp_server", "unsupported connection type: " + val);
    };
  }

  public String toExternal() {
    return name().toLowerCase();
  }
}
