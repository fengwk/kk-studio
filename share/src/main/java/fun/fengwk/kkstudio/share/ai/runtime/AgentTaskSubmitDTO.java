package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * 提交 Agent 任务的请求体：用指定 Agent 执行一段提示词，任务以独立的执行根运行，结果经返回的票据观察。
 *
 * <p>{@code maxTurns} 是可选的软 turn 预算，缺省不设上限。
 */
@Data
public class AgentTaskSubmitDTO {
  /** 必填：执行任务的 Agent 名称（须存在于 Agent 目录）。 */
  private String agent;

  /** 必填：任务提示词，非空白。 */
  private String prompt;

  /** 可选：软 turn 预算（正整数）。 */
  private Integer maxTurns;
}
