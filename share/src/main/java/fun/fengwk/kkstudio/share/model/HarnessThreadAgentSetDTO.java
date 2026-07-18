package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Thread 级 Agent 切换请求；入队 SET_AGENT（含冻结 snapshot），由 Processor 应用。 */
@Data
public class HarnessThreadAgentSetDTO {
  /** 目标 AgentDefinition id（decimal string）。 */
  private String agentDefinitionId;
}
