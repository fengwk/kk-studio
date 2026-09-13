package fun.fengwk.kkstudio.share.ai.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;

/**
 * 显式读取 MCP Server 完整配置的响应 DTO。
 *
 * <p>包含身份、版本与规范化后的完整配置严格 JSON。该端点在 HTTP 传输层必须附带 {@code Cache-Control: no-store}。
 *
 * @author fengwk
 */
@Data
public class McpServerConfigDTO {

  /** Server 稳定 UUID（canonical 小写字符串形式）。 */
  private String id;

  /** 唯一名。 */
  private String name;

  /** 当前配置的非负十进制字符串版本号。 */
  private String version;

  /** 包含默认值的 canonical 完整配置 JSON 文本。 */
  @ToString.Exclude private String configJson;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown mcp server config field: " + fieldName);
  }
}
