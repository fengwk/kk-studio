package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** live Environment 的 daemon 声明 MCP server 摘要（不含 headers/命令/URL/路径等 secrets）。 */
@Data
public class LiveEnvironmentMcpServerDTO {
  /** MCP server 名（canonical lowercase hyphenated）。 */
  private String name;

  /** 启动状态：READY 或 FAILED。 */
  private String status;

  /** 有界错误摘要；仅 FAILED 时可能非空。契约要求该字段始终存在（READY 时为显式 null）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String error;

  /** READY server 的工具 name/description 摘要。 */
  private List<LiveEnvironmentMcpToolDTO> tools;
}
