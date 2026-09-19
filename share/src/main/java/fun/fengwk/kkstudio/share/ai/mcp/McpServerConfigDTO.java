package fun.fengwk.kkstudio.share.ai.mcp;

import lombok.Data;
import lombok.ToString;

import java.util.Map;

/**
 * 显式读取 MCP Server 完整配置的响应 DTO。
 *
 * <p>headers 可能内嵌凭据，该端点在 HTTP 传输层必须附带 {@code Cache-Control: no-store}，且绝不进入日志。
 *
 * @author fengwk
 */
@Data
public class McpServerConfigDTO {

  /** 唯一名（即路径身份）。 */
  private String name;

  /** 当前配置的非负十进制字符串版本号。 */
  private String version;

  /** Streamable HTTP endpoint URL。 */
  private String url;

  /** 自定义请求 header（保留原始 {@code ${VAR}} 占位符，不回显解析后的凭据）。 */
  @ToString.Exclude private Map<String, String> headers;
}
