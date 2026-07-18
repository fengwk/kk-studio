package fun.fengwk.kkstudio.core.harness.run.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_run_event} 行映射：Run 内可恢复事件 journal。 */
@Data
public class HarnessRunEventDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Run。 */
  private Long runId;

  /** 所属 Session（查询/投影用；与 run 归属一致）。 */
  private Long sessionId;

  /** Run 内线性 sequence（与 {@code harness_run.event_sequence} 协同分配）。 */
  private Long sequence;

  /** 事件类型（assistant_delta_batch / status 等）。 */
  private String eventType;

  /** 事件 payload JSON（含 schemaVersion）。 */
  private String payloadJson;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
