package fun.fengwk.kkstudio.share.ai.mcp;

import lombok.Data;

/**
 * {@code POST /api/ai/mcp-servers} 请求体。
 *
 * @author fengwk
 */
@Data
public class McpServerCreateDTO {

  /** 必填唯一名：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符，创建后不可变。 */
  private String name;

  /** 必填 Streamable HTTP MCP endpoint URL（≤2048 字符）。 */
  private String url;

  /** 可选 Bearer token；null 表示匿名访问。 */
  private String bearerToken;

  /** 必填正整数毫秒超时；连接、发现与工具调用共用。 */
  private Long timeoutMillis;
}
