package fun.fengwk.kkstudio.share.ai.mcp;

import lombok.Data;

import java.time.Instant;

/**
 * Platform MCP Server 公开安全表示。
 *
 * <p>安全边界：仅暴露安全的元数据，绝不包含 URL、headers、env、command、cwd、bearer token 或完整配置 JSON。
 */
@Data
public class McpServerDTO {

  /** Server 稳定 UUID（canonical 小写字符串形式）。 */
  private String id;

  /** 唯一名（创建后不可变）：{@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符。 */
  private String name;

  /** 连接类型：{@code remote} 或 {@code local}。 */
  private String type;

  /** 目标 Environment UUID（local 类型必填，remote 类型为 null）。 */
  private String environmentId;

  /** 公共启用状态。 */
  private boolean enabled;

  /** 正整数毫秒超时。 */
  private Long timeoutMillis;

  /** 发现状态：{@code UNVERIFIED}、{@code AVAILABLE}、{@code FAILED}。 */
  private String discoveryStatus;

  /** 最近一次成功验证的配置版本（非负十进制字符串；未验证或变更后为 null）。 */
  private String discoveredVersion;

  /** 当前 server 下持久工具数量（仅统计可用工具）。 */
  private int toolCount;

  /** 当前配置的非负十进制字符串版本号；客户端每次更新/发现时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
