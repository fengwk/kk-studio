package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** Platform MCP server 资源模型。 */
@Data
public class McpServer {

  /** Server 全局唯一 UUID。 */
  private UUID id;

  /** 唯一名（创建后不可变）：{@code ^[a-z][a-z0-9_]*$}，≤32 字符。 */
  private String name;

  /** 连接类型：REMOTE 或 LOCAL。 */
  private McpConnectionType connectionType;

  /** 关联 Environment UUID（LOCAL 必填，REMOTE 恒为 null）。 */
  private UUID environmentId;

  /** 规范化传输配置 JSON（Remote: url/headers，Local: command/cwd/env）。 */
  @ToString.Exclude private String connectionConfig;

  /** 公共启用开关。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态：UNVERIFIED、AVAILABLE、FAILED。 */
  private McpDiscoveryStatus discoveryStatus;

  /** 最近一次成功验证的配置版本（nullable）。 */
  private Long discoveredVersion;

  /** 乐观锁行版本：非负，CAS 依据。 */
  private Long version;

  /** 创建时间。 */
  private Instant createTime;

  /** 更新时间。 */
  private Instant updateTime;
}
