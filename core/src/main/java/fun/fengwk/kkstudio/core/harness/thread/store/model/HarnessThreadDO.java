package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_thread} 行映射：持久 Branch 运行单元。 */
@Data
public class HarnessThreadDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Session tree。 */
  private Long sessionId;

  /** 当前 head entry cursor。 */
  private Long headEntryId;

  /** 持久状态：IDLE/RUNNING/WAITING/FAILED/RETRYING。 */
  private String status;

  /** 已分配 input sequence 最大值。 */
  private Long inputSequence;

  /** 当前 response debt 已消耗的自动重试次数。 */
  private Integer retryAttempt = 0;

  /** RETRYING 状态的下一次可执行时间。 */
  private LocalDateTime retryAt;

  /** 当前生效 AgentDefinition id。 */
  private Long activeAgentDefinitionId;

  /** 捕获的 Agent 名称。 */
  private String activeAgentName;

  /** Thread 级 model id。 */
  private String modelId;

  /** Thread 级 model variant。 */
  private String variant;

  /** Thread 级 YOLO；与 schema 非空默认 false 对齐。 */
  private Boolean yoloEnabled = false;

  /** 当前 processor fencing token；空表示空闲。 */
  private String processorToken;

  /** Token 租约截止。 */
  private LocalDateTime processorUntil;

  /** 乐观锁版本。 */
  private Long version;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
