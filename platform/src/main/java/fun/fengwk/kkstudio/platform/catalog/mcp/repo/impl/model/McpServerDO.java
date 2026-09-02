package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code mcp_server} 行映射：Platform MCP Streamable HTTP server 持久配置。 */
@Data
public class McpServerDO {

  /** Server 的全局唯一 UUID（应用侧生成）。 */
  private UUID id;

  /** 唯一名（创建后不可变）：{@code ^[a-z][a-z0-9_]*$}，≤32 字符。 */
  private String name;

  /** Streamable HTTP MCP endpoint URL（≤2048 字符）；敏感：错误信息绝不回显。 */
  private String url;

  /** 可空 Bearer token：只写敏感字段，任何 API 响应不回显。 */
  private String bearerToken;

  /** 正整数毫秒超时：连接、发现与工具调用共用。 */
  private Long timeoutMillis;

  /** 乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（毫秒精度），应用侧维护。 */
  private Instant updateTime;
}
