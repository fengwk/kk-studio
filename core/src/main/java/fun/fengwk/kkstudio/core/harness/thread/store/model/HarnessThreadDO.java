package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_thread} 行映射：持久执行面板 / tree cursor。 */
@Data
public class HarnessThreadDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Session tree。 */
  private Long sessionId;

  /** 当前 head entry cursor。 */
  private Long headEntryId;

  /** 冻结 agent definition id。 */
  private Long agentDefinitionId;

  /** 冻结 runtime config JSON。 */
  private String runtimeConfigJson;

  /** Thread 级 YOLO 开关。 */
  private Boolean yoloEnabled;

  /** 已分配 input sequence 最大值。 */
  private Long inputSequence;

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
