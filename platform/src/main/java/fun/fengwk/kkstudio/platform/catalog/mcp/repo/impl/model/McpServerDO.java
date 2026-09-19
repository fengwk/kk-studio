package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;

/** {@code mcp_server} 行映射。 */
@Data
public class McpServerDO {

  /** 唯一名与主键。 */
  private String name;

  /** Streamable HTTP endpoint URL。 */
  private String url;

  /** 自定义请求 header（JSON object 文本）。 */
  @ToString.Exclude private String headersJson;

  /** 公共启用开关。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态：UNVERIFIED、AVAILABLE、FAILED。 */
  private String discoveryStatus;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间。 */
  private Instant createTime;

  /** 更新时间。 */
  private Instant updateTime;
}
