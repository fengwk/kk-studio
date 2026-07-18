package fun.fengwk.kkstudio.harness.runtime.run;

import java.time.Instant;
import java.util.Objects;

/** 数据库可恢复的不可变 Agent Run 快照（对应 {@code harness_run} 一行）。 */
public record AgentRun(
    /** 业务主键（Snowflake）。 */
    long id,
    /** 所属 Session。 */
    long sessionId,
    /** 触发本 Run 的 Session Entry（通常是提交的 USER 消息；follow-up promote 时为批量中的首条）。 */
    long triggerEntryId,
    /** 持久 Run 状态机当前值。 */
    RunStatus status,
    /** 同一 Run 内已推进的 turn 序号（tool loop / 下一 turn requeue 时递增）。 */
    int turnIndex,
    /** 已被 claim 的次数。每次成功 claim 自增；与 {@code leaseOwner} 一起标识本次持有权。 */
    int attempt,
    /** 已分配的 Run Event 最大 sequence（下一事件从此递增；与 event journal 同事务维护）。 */
    long eventSequence,
    /** 当前持有执行权的 worker 标识；仅 {@link RunStatus#RUNNING} 时非空，与 {@code leaseUntil} 成对。 */
    String leaseOwner,
    /** 租约截止时间；过期后其他 worker 可 reclaim。仅 RUNNING 时非空。 */
    Instant leaseUntil,
    /** 下次允许被 claim 的时间（排队、退避、requeue 后由该字段控制）。 */
    Instant nextAttemptAt,
    /** 取消请求时间；非空表示 abort 已登记，worker/tool 应协作收敛到 CANCELLED。 */
    Instant cancelRequestedAt,
    /** 行创建时间。 */
    Instant createdAt,
    /** 首次进入执行的时间（首次 claim 时写入，之后保持）。 */
    Instant startedAt,
    /** 进入终态的时间；非终态必须为 null，终态必须非 null。 */
    Instant finishedAt,
    /** 行最近更新时间。 */
    Instant updatedAt) {

  public AgentRun {
    if (id <= 0 || sessionId <= 0 || triggerEntryId <= 0) {
      throw new IllegalArgumentException("run, session and trigger entry ids must be positive");
    }
    status = Objects.requireNonNull(status, "status");
    if (turnIndex < 0 || attempt < 0 || eventSequence < 0) {
      throw new IllegalArgumentException("run counters must not be negative");
    }
    if ((leaseOwner == null) != (leaseUntil == null)) {
      throw new IllegalArgumentException("lease owner and lease until must be set together");
    }
    if (status != RunStatus.RUNNING && leaseOwner != null) {
      throw new IllegalArgumentException("only running runs may hold a lease");
    }
    if (status.terminal() != (finishedAt != null)) {
      throw new IllegalArgumentException("terminal run must have exactly one finished timestamp");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
  }

  public boolean isOwnedBy(String owner, int claimedAttempt) {
    return status == RunStatus.RUNNING
        && Objects.equals(leaseOwner, owner)
        && attempt == claimedAttempt;
  }
}
