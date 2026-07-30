package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.LocalDateTime;

/** HarnessThread 查询投影；id 均为 decimal string。 */
@Data
public class HarnessThreadDTO {
  /** Thread 主键。 */
  private String threadId;

  /** 当前 Session 主键（由 head Entry 派生，可空）。 */
  private String sessionId;

  /** 当前 Session 标题（由 head Entry 派生，可空）。 */
  private String sessionTitle;

  /** 当前 head Entry（可空）。 */
  private String headEntryId;

  /** 当前 execution epoch。 */
  private Long executionEpoch;

  /** PostgreSQL authoritative durable projection cursor (decimal bigint string). */
  private String revision;

  /** 展示状态（query 派生，非 durable 列）：{@code RUNNING > WAITING > RUNNABLE > UNBOUND/IDLE}。 */
  private String status;

  /** 已分配 input sequence 高水位。 */
  private Long inputSequence;

  /** 当前 AgentDefinition id（可空）。 */
  private String activeAgentDefinitionId;

  /** 当前 Agent 名称（可空）。 */
  private String activeAgentName;

  /** Thread 级 model id（可空）。 */
  private String modelId;

  /** Thread 级 model variant（可空）。 */
  private String variant;

  /** Thread 级 YOLO。 */
  private Boolean yoloEnabled;

  /** 是否正在被 Reconciler 持有（processor lease 未过期）。 */
  private Boolean processing;

  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
