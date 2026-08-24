package fun.fengwk.kkstudio.platform.catalog.provider.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code agent_provider} 行映射：LLM Provider 连接配置。 */
@Data
public class AgentProviderDO {

  /** Provider 唯一名称（主键，varchar(64)，不允许首尾空白与 '/'）。 */
  private String name;

  /** 描述，可选；varchar(512)，null 表示未填写。 */
  private String description;

  /** Provider 协议类型稳定 wire 值，映射 varchar(64)，必填。 */
  private String providerType;

  /** 服务 base URL，可选；varchar(512)。 */
  private String baseUrl;

  /** 访问凭据（API Key 等），敏感字段：不暴露于公共 DTO，更新时未显式提供则保留当前已保存值；仅在构造 Provider 运行时适配器时使用。 */
  private String credential;

  /**
   * Provider 内部配置 JSON（模型调用超时策略 modelCallTimeoutMillis / modelCallIdleTimeoutMillis 等），映射 {@code
   * config} jsonb 列，必填。
   */
  private String configJson;

  /** 乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
