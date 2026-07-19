package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** AgentThread 查询投影；id 均为 decimal string。 */
@Data
public class HarnessThreadDTO {
  /** Thread 主键。 */
  private String threadId;

  /** 所属 Session 主键。 */
  private String sessionId;

  /** 所属 Session 标题（列表/卡片展示用）。 */
  private String sessionTitle;

  /** 当前 head Entry。 */
  private String headEntryId;

  /** durable actor 状态。 */
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

  /** 是否正在被 Processor 持有（token 未过期）。 */
  private Boolean processing;

  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
