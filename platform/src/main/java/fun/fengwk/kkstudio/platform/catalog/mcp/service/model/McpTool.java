package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import lombok.Data;
import lombok.ToString;

import java.util.UUID;

/** 已持久化的 MCP 工具行。 */
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

  /** 远端工具描述。 */
  @ToString.Exclude private String description;

  /** 远端工具 JSON input schema（canonical JSON object 文本）。 */
  @ToString.Exclude private String inputSchemaJson;

  /** 模式修订代际（非负，定义变更或下线重现时递增）。 */
  private Long schemaRevision;

  /** 是否可用（远端消失时 tombstone 为 false 保留稳定 UUID）。 */
  private boolean available;
}
