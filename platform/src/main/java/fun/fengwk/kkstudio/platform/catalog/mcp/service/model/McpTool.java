package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import lombok.Data;

import java.util.UUID;

/** 已冻结的 MCP 远端工具行（内部模型）。 */
@Data
public class McpTool {

  /** 全局唯一稳定 UUID（身份来源）。 */
  private UUID id;

  /** 所属 MCP server id。 */
  private UUID serverId;

  /** 远端工具原始名（同一 server 内唯一，既有行不可变）。 */
  private String sourceName;

  /** 全局唯一模型可见工具名。 */
  private String modelName;

  /** 远端工具描述（非空白）。 */
  private String description;

  /** 远端工具 JSON input schema（canonical JSON object 文本）。 */
  private String inputSchemaJson;
}
