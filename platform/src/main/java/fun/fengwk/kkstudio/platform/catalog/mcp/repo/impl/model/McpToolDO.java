package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code mcp_tool} 行映射：从远端 MCP server 发现并冻结的工具行。 */
@Data
public class McpToolDO {

  /** 工具的全局唯一稳定 UUID（AgentToolId / model_name 的身份来源）。 */
  private UUID id;

  /** 所属 MCP server id；随父行删除级联硬删除。 */
  private UUID serverId;

  /** 远端 MCP 工具原始名（同一 server 内唯一，既有行不可变）。 */
  private String sourceName;

  /** 全局唯一模型可见工具名。 */
  private String modelName;

  /** 远端工具描述（非空白）。 */
  private String description;

  /** 远端工具 JSON input schema（JSON object 文本）。 */
  private String inputSchemaJson;
}
