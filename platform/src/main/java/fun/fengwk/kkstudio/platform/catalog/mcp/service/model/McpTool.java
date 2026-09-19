package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import lombok.Data;
import lombok.ToString;

/** MCP server 当前发现结果的工具行：name 是模型可见身份与主键。 */
@Data
public class McpTool {

  /** 模型可见工具名（主键）：{@code mcp_<server_name>_<normalized_source_tool_name>}。 */
  private String name;

  /** 所属 MCP server name。 */
  private String serverName;

  /** 远端工具原始名（同一 server 内唯一）。 */
  private String sourceName;

  /** 远端工具描述。 */
  @ToString.Exclude private String description;

  /** 远端工具 JSON input schema（canonical JSON object 文本）。 */
  @ToString.Exclude private String inputSchemaJson;
}
