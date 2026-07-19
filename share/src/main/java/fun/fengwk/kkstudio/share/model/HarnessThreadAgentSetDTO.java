package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Thread 级 Agent 切换请求；入队 SET_AGENT（仅 id，name 由服务端捕获），由 Processor 有序应用。 */
@Data
public class HarnessThreadAgentSetDTO {
  /** 目标 AgentDefinition id（decimal string）。 */
  private String agentDefinitionId;

  /** 外部请求幂等键。 */
  private String clientMessageId;
}
