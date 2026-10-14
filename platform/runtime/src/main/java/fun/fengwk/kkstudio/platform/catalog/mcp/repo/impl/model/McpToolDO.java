package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model;

import lombok.Data;
import lombok.ToString;

/** {@code mcp_tool} 行映射。 */
@Data
public class McpToolDO {

  /** 模型可见工具名与主键。 */
  private String name;

  /** 所属 MCP server name。 */
  private String serverName;

  /** 远端 MCP 工具原始名。 */
  private String sourceName;

  /** 远端工具描述。 */
  @ToString.Exclude private String description;

  /** 远端工具 JSON input schema（JSON object 文本）。 */
  @ToString.Exclude private String inputSchemaJson;
}
