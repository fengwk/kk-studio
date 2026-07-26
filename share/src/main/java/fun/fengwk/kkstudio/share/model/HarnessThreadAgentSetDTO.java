package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Thread 级 Agent 切换请求；服务端解析 Definition 并冻结完整配置快照后入队 SET_AGENT。 */
@Data
public class HarnessThreadAgentSetDTO {
  private Long expectedExecutionEpoch;

  /** 目标 AgentDefinition id（decimal string）。 */
  private String agentDefinitionId;

  /** 外部请求幂等键。 */
  private String clientMessageId;
}
