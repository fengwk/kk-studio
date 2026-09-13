package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** {@code mcp_server} 行映射。 */
@Data
public class McpServerDO {

  /** Server 全局唯一 UUID。 */
  private UUID id;

  /** 唯一名：{@code ^[a-z][a-z0-9_]*$}，≤32 字符。 */
  private String name;

  /** 连接类型：REMOTE 或 LOCAL。 */
  private String connectionType;

  /** 关联 Environment UUID（LOCAL 必填，REMOTE 为 null）。 */
  private UUID environmentId;

  /** 传输参数 JSONB 字符串。 */
  @ToString.Exclude private String connectionConfig;

  /** 公共启用开关。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态：UNVERIFIED、AVAILABLE、FAILED。 */
  private String discoveryStatus;

  /** 最近一次成功验证的配置版本（nullable）。 */
  private Long discoveredVersion;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间。 */
  private Instant createTime;

  /** 更新时间。 */
  private Instant updateTime;
}
