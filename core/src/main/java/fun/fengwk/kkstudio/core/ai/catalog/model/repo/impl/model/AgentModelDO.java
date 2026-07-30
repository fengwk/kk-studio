package fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code agent_model} 行映射：LLM 模型条目（绑定 provider_id）。 */
@Data
public class AgentModelDO {

  /** 业务主键。 */
  private Long id;

  /** 绑定 Provider id。 */
  private Long providerId;

  /** 在所属 Provider 内唯一的模型名称。 */
  private String name;

  /** 描述。 */
  private String description;

  /** 可执行配置 JSON（限价/能力/价格/variants 等）。 */
  private String configJson;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz）。 */
  private Instant updateTime;
}
