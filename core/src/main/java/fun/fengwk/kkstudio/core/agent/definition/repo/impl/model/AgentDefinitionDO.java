package fun.fengwk.kkstudio.core.agent.definition.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code agent_definition} 行映射：可创建 Session 的 Agent 定义。 */
@Data
public class AgentDefinitionDO {

  /** 业务主键。 */
  private Long id;

  /** Agent 唯一名称。 */
  private String name;

  /** 描述。 */
  private String description;

  /** 系统提示词。 */
  private String systemPrompt;

  /** 默认绑定的 model id。 */
  private Long modelId;

  /** 覆盖 model.defaultVariant；null/blank 表示使用模型默认。 */
  private String variant;

  /** Agent 配置 JSON（tools/skills/executionPolicy 等）。 */
  private String configJson;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
