package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** live Environment 的 daemon 声明 MCP 工具摘要（稳定只读展示，不作为动态可选的 Agent 工具）。 */
@Data
public class LiveEnvironmentMcpToolDTO {
  /** MCP 工具名。 */
  private String name;

  /** MCP 工具描述。 */
  private String description;
}
