package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;

import java.time.Instant;

/** MCP server 列表项：只含列表页需要的安全元数据，不含 URL 与 headers。 */
@Data
public class McpServerSummaryDTO {

  /** 唯一名（主键与路径身份，创建后不可变）：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符。 */
  private String name;

  /** 公共启用状态。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态。 */
  private McpDiscoveryStatus discoveryStatus;

  /** 当前 server 下已发现的工具数量。 */
  private int toolCount;

  /** 当前配置的非负十进制字符串版本号；客户端每次更新/发现时必须回传。 */
  private String version;

  /** 创建时间。 */
  private Instant createdAt;

  /** 更新时间。 */
  private Instant updatedAt;
}
