package fun.fengwk.kkstudio.core.harness.run.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_run} 行映射：一次可调度、可恢复的 Agent 执行单元。 */
@Data
public class HarnessRunDO {
  /** 业务主键（Snowflake）。 */
  private Long id;

  /** 所属 Session。 */
  private Long sessionId;

  /** 触发本 Run 的 Session Entry（通常是提交的 USER 消息；follow-up promote 时为批量中的首条）。 */
  private Long triggerEntryId;

  /**
   * 持久状态：{@code QUEUED}/{@code RUNNING}/{@code WAITING_TOOLS}/ {@code SUCCEEDED}/{@code
   * FAILED}/{@code CANCELLED} 等。
   */
  private String status;

  /** 同一 Run 内已推进的 turn 序号（tool loop / requeueAdvanceTurn 时递增）。 */
  private Integer turnIndex;

  /** 已被 claim 的次数。每次成功 claim 自增；与 {@link #leaseOwner} 一起做 CAS 校验。 */
  private Integer attempt;

  /** 已分配的 Run Event 最大 sequence（下一事件从此递增；与 event journal 同事务维护）。 */
  private Long eventSequence;

  /** 当前持有执行权的 worker 标识；仅 {@code RUNNING} 时非空，与 {@link #leaseUntil} 成对。 */
  private String leaseOwner;

  /** 租约截止时间；过期后其他 worker 可 reclaim。仅 {@code RUNNING} 时非空。 */
  private LocalDateTime leaseUntil;

  /** 下次允许被 claim 的时间（排队、退避、requeue 后由该字段控制）。 */
  private LocalDateTime nextAttemptAt;

  /** 取消请求时间；非空表示 abort 已登记，worker/tool 应协作收敛到 CANCELLED。 */
  private LocalDateTime cancelRequestedAt;

  /** 行创建时间（映射列 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 首次进入执行的时间（首次 claim 时写入，之后保持）。 */
  private LocalDateTime startedAt;

  /** 进入终态（SUCCEEDED/FAILED/CANCELLED）的时间；非终态必须为空。 */
  private LocalDateTime finishedAt;

  /** 行最近更新时间（映射列 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
