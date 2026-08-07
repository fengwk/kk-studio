package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;

/** 不含凭证的 Provider 公开表示。 */
@Data
public class AgentProviderDTO {

  /** Provider 唯一名（资源路由身份）：非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;

  /** 可空描述（≤512 字符）。 */
  private String description;

  /**
   * 供应商类型，取 {@link AgentProviderType} 枚举名（openai/openai_response/anthropic/google，wire 值即小写枚举名）。
   */
  private String providerType;

  /** 可空模型 API base URL（≤512 字符）。 */
  private String baseUrl;

  /** 是否已配置凭证（内部 credential 非空白即为 true）；凭证本身永不进入公开 DTO。 */
  private boolean configured;

  /** 模型调用总超时（毫秒，正数）；未配置时由产品默认值填充。 */
  private Long modelCallTimeoutMillis;

  /** 模型调用空闲超时（毫秒，正数）；未配置时由产品默认值填充。 */
  private Long modelCallIdleTimeoutMillis;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
