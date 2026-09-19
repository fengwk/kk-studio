package fun.fengwk.kkstudio.share.ai.mcp;

import lombok.Data;

import java.time.Instant;

/**
 * Platform MCP Server 公开安全表示。
 *
 * <p>安全边界：仅暴露安全元数据，绝不包含 URL、headers、bearer token 或 {code ${VAR}} 解析后的值。
 *
 * @author fengwk
 */
@Data
public class McpServerDTO {

  /** 唯一名（主键与路径身份，创建后不可变）：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符。 */
  private String name;

  /** 公共启用状态。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态：{@code UNVERIFIED}、{@code AVAILABLE}、{@code FAILED}。 */
  private String discoveryStatus;

  /** 当前 server 下已发现的工具数量。 */
  private int toolCount;

  /** 当前配置的非负十进制字符串版本号；客户端每次更新/发现时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
