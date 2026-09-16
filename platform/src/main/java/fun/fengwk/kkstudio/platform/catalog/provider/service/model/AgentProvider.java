package fun.fengwk.kkstudio.platform.catalog.provider.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Instant;
import java.util.UUID;

/** 全局 Agent provider 资源。 */
@Data
public class AgentProvider {

  /** Provider 唯一名称（主键，映射 {@code agent_provider.name}，最长 64，不允许首尾空白与 '/'）。 */
  private String name;

  /** 描述，可选；映射 text 列，null 表示未填写。 */
  private String description;

  /** Provider 协议类型，必填。 */
  private ProviderType providerType;

  /** 服务 base URL，可选；映射 varchar(512)。 */
  private String baseUrl;

  /** 访问凭据（API Key 等），敏感字段：不暴露于公共 DTO，更新时未显式提供则保留当前已保存值；仅在构造 Provider 运行时适配器时使用。 */
  private String credential;

  /**
   * Provider 内部配置 JSON（模型调用超时策略 modelCallTimeoutMillis / modelCallIdleTimeoutMillis 等），映射 {@code
   * config} jsonb 列，必填。
   */
  private String configJson;

  /** Provider 协议/凭据连接世代标识（UUID，仅配置或凭据变更时轮换）。 */
  private UUID connectionGenerationId;

  /** 乐观锁版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
