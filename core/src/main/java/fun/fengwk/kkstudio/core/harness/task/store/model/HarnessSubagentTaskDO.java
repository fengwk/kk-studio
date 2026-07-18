package fun.fengwk.kkstudio.core.harness.task.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_subagent_task} 行映射：父 ToolInvocation 到 child Session/Thread 的 durable 关系。 */
@Data
public class HarnessSubagentTaskDO {
  /** 主键：父 task ToolInvocation id（执行幂等键）。 */
  private Long parentInvocationId;

  /** 父 Session id。 */
  private Long parentSessionId;

  /** 父 Thread id。 */
  private Long parentThreadId;

  /** 子 Session id。 */
  private Long childSessionId;

  /** 子 Thread id。 */
  private Long childThreadId;

  /** 目标子 Agent 标识。 */
  private String targetAgent;

  /** working-copy 策略。 */
  private String workingCopyPolicy;

  /** working-copy 修订标识。 */
  private String workingCopyRevision;

  /** 子任务最大 turn 数。 */
  private Integer maxTurns;

  /** 任务状态。 */
  private String status;

  /** 终态 report JSON。 */
  private String reportJson;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
