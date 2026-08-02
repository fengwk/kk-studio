package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/** HarnessThread 查询投影；id 均为 decimal string。 */
@Data
public class HarnessThreadDTO {
  /** Thread 主键。 */
  private String threadId;

  /** 当前 Session 主键（由 head Entry 派生）。 */
  private String sessionId;

  /** 当前 Session 标题（由 head Entry 派生）。 */
  private String sessionTitle;

  /** 当前 head Entry。 */
  private String headEntryId;

  /** 当前 Thread 选择的 Environment；为空表示仅使用平台工具。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  /** 当前 execution epoch。 */
  private Long executionEpoch;

  /** PostgreSQL authoritative durable projection cursor (decimal bigint string). */
  private String revision;

  /** 展示状态（query 派生，非 durable 列）：{@code RUNNING > WAITING > RUNNABLE > IDLE}。 */
  private String status;

  /** 已分配 input sequence 高水位。 */
  private Long inputSequence;

  /** 是否正在被 Reconciler 持有（processor lease 未过期）。 */
  private Boolean processing;

  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
