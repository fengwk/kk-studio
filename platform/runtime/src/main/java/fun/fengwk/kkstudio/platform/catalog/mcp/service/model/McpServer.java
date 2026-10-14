package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.Map;

/** Platform MCP server 资源模型：name 即主键与不可变身份。 */
@Data
public class McpServer {

  /** 唯一名（创建后不可变）：{@code ^[a-z][a-z0-9_]*$}，≤32 字符。 */
  private String name;

  /** Streamable HTTP endpoint URL。 */
  private String url;

  /** 自定义请求 header（可能内嵌凭据，绝不进入日志）。 */
  @ToString.Exclude private Map<String, String> headers;

  /** 公共启用开关。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态：UNVERIFIED、AVAILABLE、FAILED。 */
  private McpDiscoveryStatus discoveryStatus;

  /** 乐观锁行版本：非负，CAS 依据。 */
  private Long version;

  /** 创建时间。 */
  private Instant createTime;

  /** 更新时间。 */
  private Instant updateTime;
}
