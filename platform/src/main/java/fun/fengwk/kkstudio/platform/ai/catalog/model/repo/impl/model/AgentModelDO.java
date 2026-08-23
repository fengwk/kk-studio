package fun.fengwk.kkstudio.platform.ai.catalog.model.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code agent_model} 行映射：LLM 模型条目（绑定 provider_name）。 */
@Data
public class AgentModelDO {

  /** 绑定 Provider 名称（必填，复合主键一部分，外键引用 agent_provider.name）。 */
  private String providerName;

  /** 上游 Provider 的模型名称（必填，与 provider_name 组成复合主键）。 */
  private String name;

  /** 描述，可选；varchar(512)，null 表示未填写。 */
  private String description;

  /**
   * 结构化配置 JSON（AgentModelConfigDTO：limit / abilities / pricing / variants / defaultVariant），映射
   * {@code config} jsonb 列，必填。
   */
  private String configJson;

  /** 乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
