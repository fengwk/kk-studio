package fun.fengwk.kkstudio.core.ai.runtime.goal.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code agent_thread_goal} 行映射：Thread 当前 durable goal。 */
@Data
public class HarnessThreadGoalDO {

  /** 主键：Thread id（外键引用 harness_thread.id）。 */
  private Long threadId;

  /** 目标客观描述（text，必填：去除首尾空白后非空）。 */
  private String objective;

  /** 可选 token 预算；null 表示无上限，设置时必须 > 0。 */
  private Long tokenBudget;

  /** 状态，必填：active / complete / blocked。 */
  private String status;

  /** 终态原因：active 时必须为 null，complete / blocked 时必填且非空白。 */
  private String reason;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private OffsetDateTime createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，毫秒精度，约束不早于创建时间）。 */
  private OffsetDateTime updateTime;
}
