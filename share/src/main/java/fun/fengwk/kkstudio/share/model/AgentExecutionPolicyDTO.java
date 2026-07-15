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
  /**
   * freeze-time control consumption mode for steering inputs. Null/absent at
   * persist-time means the frozen snapshot defaults to {@code ONE_AT_A_TIME};
   * any non-null value must parse strictly to the enum in
   * {@code ControlConsumptionMode}.
   */
  private String steeringMode;
  /**
   * freeze-time control consumption mode for follow-up inputs. Same
   * null/default/parse rules as {@link #steeringMode}.
   */
  private String followUpMode;
}
