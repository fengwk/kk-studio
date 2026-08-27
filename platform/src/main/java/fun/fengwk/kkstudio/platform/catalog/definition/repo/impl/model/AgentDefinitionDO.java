package fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code agent_definition} 行映射：绑定 provider/model 名称的全局 Agent 定义。 */
@Data
public class AgentDefinitionDO {

  /** 全局唯一名称（主键，varchar(64)，不允许首尾空白与 '/'）。 */
  private String name;

  /** 描述，可选；varchar(512)，null 表示未填写。 */
  private String description;

  /** 系统提示词，可选；text 列。 */
  private String systemPrompt;

  /** 绑定的 Model provider 名称（必填，与 model_name 组成复合外键引用 agent_model）。 */
  private String modelProviderName;

  /** 绑定的 Model 名称（必填，与 model_provider_name 组成复合外键引用 agent_model）。 */
  private String modelName;

  /** 可选 Variant 覆盖（null/空表示不覆盖，运行时解析为 Model 配置的 defaultVariant）。 */
  private String variant;

  /** 可执行配置 JSON（toolIds/skills/subagents 列表），映射 {@code config} jsonb 列，必填。 */
  private String configJson;

  /** 乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
