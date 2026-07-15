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

  /** {@code ONE_AT_A_TIME} 或 {@code ALL}；null 表示由 frozen snapshot decode 决定默认值。 */
  private String steeringMode;

  /** {@code ONE_AT_A_TIME} 或 {@code ALL}；null 表示由 frozen snapshot decode 决定默认值。 */
  private String followUpMode;
}
