package fun.fengwk.kkstudio.core.agent.model.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code agent_model} 行映射：模型资源与配置 JSON。 */
@Data
public class AgentModelDO {

  /** 业务主键。 */
  private Long id;

  /** 所属 Provider id。 */
  private Long providerId;

  /** 模型唯一名称。 */
  private String name;

  /** 描述。 */
  private String description;

  /** 结构化模型配置 JSON（limit/abilities/pricing/defaultVariant/variants）。 */
  private String configJson;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz）。 */
  private OffsetDateTime createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz）。 */
  private OffsetDateTime updateTime;
}
