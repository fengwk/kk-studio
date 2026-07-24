package fun.fengwk.kkstudio.core.harness.goal.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code harness_thread_goal} 行映射：Thread 当前 durable goal。 */
@Data
public class HarnessThreadGoalDO {
  /** 主键：Thread id。 */
  private Long threadId;

  /** 目标客观描述。 */
  private String objective;

  /** 可选 token 预算；null 表示无上限。 */
  private Long tokenBudget;

  /** 状态：active / complete / blocked。 */
  private String status;

  /** 终态原因；active 时为 null。 */
  private String reason;

  /** 创建时间（映射 {@code created_at}）。 */
  private OffsetDateTime createTime;

  /** 更新时间（映射 {@code updated_at}）。 */
  private OffsetDateTime updateTime;
}
