package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Agent 任务的当前状态：票据与执行它的 Thread，加上冻结结果。
 *
 * <p>{@code state} 为 {@code RUNNING} 时尚无结果；结束后 {@code COMPLETED} 带 {@code report}，{@code ERROR} /
 * {@code CANCELLED} 带 {@code error} 与可选的 {@code partialResult}。
 */
@Data
public class AgentTaskDTO {
  /** 票据 id（canonical UUID string），观察、停止与读取结果都用它。 */
  private String ticketId;

  /** 执行任务的 Thread id。 */
  private String threadId;

  /** 执行任务的 Agent 名称。 */
  private String agent;

  /** RUNNING / COMPLETED / ERROR / CANCELLED。 */
  private String state;

  /** 任务完成后的最终报告。 */
  private String report;

  /** 任务失败或取消时已产出的部分结果。 */
  private String partialResult;

  /** 任务失败或取消的原因。 */
  private String error;
}
