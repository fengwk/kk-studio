package fun.fengwk.kkstudio.platform.catalog.definition.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.time.Instant;

/** 全局 Agent definition 资源。 */
@Data
public class AgentDefinition {

  /** 全局唯一名称（主键，映射 {@code agent_definition.name}，最长 64，不允许首尾空白与 '/'）。 */
  private String name;

  /** 系统持有的所有权类型（映射 {@code agent_definition.type}）：用户创建为 USER，内置为 BUILTIN。 */
  private AgentDefinitionType type;

  /** 描述，可选；映射 text 列，null 表示未填写。 */
  private String description;

  /** 系统提示词，可选；映射 text 列。 */
  private String systemPrompt;

  /**
   * 绑定的 Model provider 名称；与 {@link #modelName} 组成复合外键引用 {@code agent_model}。USER 必填；未配置模型的内置 Agent
   * 两列同为 null。
   */
  private String modelProviderName;

  /**
   * 绑定的 Model 名称；与 {@link #modelProviderName} 组成复合外键引用 {@code agent_model}。USER 必填；未配置模型的内置 Agent
   * 两列同为 null。
   */
  private String modelName;

  /** 可选 Variant 覆盖；null/空表示不覆盖，运行时解析为 Model 配置的 defaultVariant。 */
  private String variant;

  /** 可执行配置 JSON（tools/skills/subagents/inheritParentEnvironment），映射 {@code config} jsonb 列，必填。 */
  private String configJson;

  /** 乐观锁版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
