package fun.fengwk.kkstudio.core.agent.provider.repo.impl.model;

import lombok.Data;

import fun.fengwk.kkstudio.share.model.AgentProviderType;

import java.time.Instant;

/** {@code agent_provider} 行映射：LLM Provider 连接配置。 */
@Data
public class AgentProviderDO {

  /** 业务主键。 */
  private Long id;

  /** Provider 唯一名称。 */
  private String name;

  /** 描述。 */
  private String description;

  /** Provider 协议类型（如 openai / anthropic）。 */
  private AgentProviderType providerType;

  /** 服务 base URL。 */
  private String baseUrl;

  /** 访问凭据（API Key 等）。 */
  private String credential;

  /** Provider 配置 JSON（超时等）。 */
  private String configJson;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz）。 */
  private Instant updateTime;
}
