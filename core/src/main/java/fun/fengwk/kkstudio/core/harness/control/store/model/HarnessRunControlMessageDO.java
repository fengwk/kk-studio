package fun.fengwk.kkstudio.core.harness.control.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code harness_run_control_message} 行映射：steer/follow-up 持久化控制队列。
 *
 * <p>{@link #runId} 可为 null（FOLLOW_UP 在无 active run 时直接 promotion）。
 */
@Data
public class HarnessRunControlMessageDO {
  /** 控制消息主键（独立 Snowflake namespace）。 */
  private Long id;

  /** 目标 Session。 */
  private Long sessionId;

  /** 接受时的 active Run；FOLLOW_UP 直接 promotion 时为空。 */
  private Long runId;

  /** 控制类型：STEER / FOLLOW_UP。 */
  private String controlKind;

  /** 冻结消费模式：ONE_AT_A_TIME / ALL（来自 Agent Snapshot）。 */
  private String consumptionMode;

  /** 编码后的 USER AgentMessage JSON。 */
  private String messageJson;

  /** 状态：PENDING / CONSUMED / CLEARED / PROMOTED。 */
  private String status;

  /** 消费或 promote 后写入的目标 Run id。 */
  private Long consumedRunId;

  /** 消费或 promote 后产生的 Session Entry id。 */
  private Long consumedEntryId;

  /** 入库时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 离开 PENDING 的时间（CLEARED/CONSUMED/PROMOTED）。 */
  private LocalDateTime consumedAt;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
