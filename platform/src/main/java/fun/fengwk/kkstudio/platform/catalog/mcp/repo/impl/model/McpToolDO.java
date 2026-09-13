package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model;

import lombok.Data;
import lombok.ToString;

import java.util.UUID;

/** {@code mcp_tool} 行映射。 */
@Data
public class McpToolDO {

  /** 工具全局唯一稳定 UUID。 */
  private UUID id;

  /** 所属 MCP server id。 */
  private UUID serverId;

  /** 远端 MCP 工具原始名。 */
  private String sourceName;

  /** 全局唯一模型可见工具名。 */
  private String modelName;

  /** 远端工具描述。 */
  @ToString.Exclude private String description;

  /** 远端工具 JSON input schema（JSON object 文本）。 */
  @ToString.Exclude private String inputSchemaJson;

  /** 模式修订代际（>=0）。 */
  private Long schemaRevision;

  /** 是否可用（tombstone 标记）。 */
  private boolean available;
}
