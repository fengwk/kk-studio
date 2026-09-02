package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Platform MCP server 资源（内部模型，含敏感 bearer token；公开 DTO 绝不回显）。 */
@Data
public class McpServer {

  /** Server 的全局唯一 UUID。 */
  private UUID id;

  /** 唯一名（创建后不可变）：{@code ^[a-z][a-z0-9_]*$}，≤32 字符。 */
  private String name;

  /** Streamable HTTP MCP endpoint URL（≤2048 字符）；错误信息绝不回显。 */
  private String url;

  /** 可空 Bearer token；只写敏感字段。 */
  private String bearerToken;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 乐观锁版本：非负，每次写操作 +1。 */
  private Long version;

  /** 创建时间（毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（毫秒精度）。 */
  private Instant updateTime;
}
