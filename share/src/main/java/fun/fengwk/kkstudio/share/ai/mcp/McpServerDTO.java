package fun.fengwk.kkstudio.share.ai.mcp;

import lombok.Data;

import java.time.Instant;

/** 不含 Bearer token 的 MCP Server 公开表示；token 是只写敏感字段，任何响应都不回显。 */
@Data
public class McpServerDTO {

  /** Server 稳定 UUID（canonical 小写字符串形式）。 */
  private String id;

  /** 唯一名：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符，创建后不可变。 */
  private String name;

  /** Streamable HTTP MCP endpoint URL（≤2048 字符）。 */
  private String url;

  /** 是否已配置 Bearer token（token 本身永不进入公开 DTO）。 */
  private boolean bearerTokenConfigured;

  /** 正整数毫秒超时；连接、发现与工具调用共用。 */
  private Long timeoutMillis;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
