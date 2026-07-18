package fun.fengwk.kkstudio.core.agent.model.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code agent_model} 行映射：模型资源与定价/能力配置。 */
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

  /** 能力 JSON 数组（如 TEXT/TOOLS）。 */
  private String capabilitiesJson;

  /** 模型配置 JSON（contextWindow、variants、pricing 等）。 */
  private String configJson;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
