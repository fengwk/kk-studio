package fun.fengwk.kkstudio.core.ai.catalog.definition.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code agent_definition} 行映射：绑定 provider/model 名称的全局 Agent 定义。 */
@Data
public class AgentDefinitionDO {

  /** 全局唯一名称。 */
  private String name;

  /** 描述。 */
  private String description;

  /** 系统提示词。 */
  private String systemPrompt;

  /** 绑定的 Model provider/name composite identity. */
  private String modelProviderName;

  private String modelName;

  /** 可选 Variant 覆盖（null 表示沿用 Model.defaultVariant）。 */
  private String variant;

  /** 可执行配置 JSON（环境/工具/技能）。 */
  private String configJson;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz）。 */
  private Instant updateTime;

  /** 软删除时间（映射 {@code deleted_at} timestamptz）。 */
  private Instant deletedAt;
}
