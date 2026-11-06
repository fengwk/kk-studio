package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO;

import java.time.Instant;

/**
 * MCP server 详情：URL 是普通字段，headers 可能内嵌凭据因而只陈述是否已配置。
 *
 * <p>安全边界：绝不回显 headers、bearer token 或 {@code ${VAR}} 解析后的值。
 */
@Data
public class McpServerDTO {

  /** 唯一名（主键与路径身份，创建后不可变）：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符。 */
  private String name;

  /** Streamable HTTP endpoint URL。 */
  private String url;

  /** 公共启用状态。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态。 */
  private McpDiscoveryStatus discoveryStatus;

  /** 自定义请求 header 的配置事实；值为 SET 保存的那份 header 映射。 */
  private SecretConfiguredDTO headers;

  /** 当前 server 下已发现的工具数量。 */
  private int toolCount;

  /** 当前配置的非负十进制字符串版本号；客户端每次更新/发现时必须回传。 */
  private String version;

  /** 创建时间。 */
  private Instant createdAt;

  /** 更新时间。 */
  private Instant updatedAt;
}
