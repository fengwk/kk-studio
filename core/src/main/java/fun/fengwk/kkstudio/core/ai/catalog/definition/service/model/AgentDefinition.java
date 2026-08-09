package fun.fengwk.kkstudio.core.ai.catalog.definition.service.model;

import lombok.Data;

import java.time.Instant;

/** 全局 Agent definition 资源。 */
@Data
public class AgentDefinition {

  /** 全局唯一名称（主键，映射 {@code agent_definition.name}，最长 64，不允许首尾空白与 '/'）。 */
  private String name;

  /** 描述，可选；映射 varchar(512)，null 表示未填写。 */
  private String description;

  /** 系统提示词，可选；映射 text 列。 */
  private String systemPrompt;

  /** 绑定的 Model provider 名称（必填，与 {@link #modelName} 组成复合外键引用 {@code agent_model}）。 */
  private String modelProviderName;

  /** 绑定的 Model 名称（必填，与 {@link #modelProviderName} 组成复合外键引用 {@code agent_model}）。 */
  private String modelName;

  /** 可选 Variant 覆盖；null/空表示不覆盖，运行时解析为 Model 配置的 defaultVariant。 */
  private String variant;

  /** 可执行配置 JSON（tools/skills/subagents 短名列表），映射 {@code config} jsonb 列，必填。 */
  private String configJson;

  /** 乐观锁版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
