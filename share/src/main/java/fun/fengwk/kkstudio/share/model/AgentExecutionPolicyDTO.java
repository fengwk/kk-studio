package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * Bounded execution policy persisted inside an agent definition configuration.
 *
 * @author fengwk
 */
@Data
public class AgentExecutionPolicyDTO {

  private Integer maxTurns;
  private Integer maxDepth;
  private Integer maxDirectSubagents;
  private Integer maxTotalSubagents;
  private Long idleTimeoutMillis;
  private Long runTimeoutMillis;
}
