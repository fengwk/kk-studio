package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已持久化的异步 task 委派事实：以 task Tool invocation 为主键，记录父子/根 Thread、本次执行边界、目标 Agent 与完整 prompt，以及执行终态与
 * 父通知状态。
 *
 * <p>同一子 Thread 可以有多次执行（多行）；任一时刻至多一行 {@link SubagentTaskStatus#OPEN}，由数据库部分唯一索引保证。
 *
 * <p>终态与报告在 {@link SubagentTaskStatus#SETTLED} 时确定并持久化，交付内容以本记录为准：子历史此后被继续或删除都不会改变已终结的结果。
 *
 * <p>{@code createdAt}/{@code updatedAt}/{@code settledAt} 是数据库时间戳的只读投影，写入路径不绑定 Java 时间类型。
 *
 * @param invocationId task Tool invocation UUID（主键，稳定幂等身份）
 * @param parentThreadId 发起委派的父 Thread UUID
 * @param rootThreadId 委派树根 Thread UUID
 * @param childSessionId 子 Session UUID
 * @param childThreadId 子 Thread UUID
 * @param sourceHeadEntryId 本次执行边界（接受时子 Thread 的 head Entry）
 * @param agent 本次执行的 Agent 名
 * @param prompt 本次委派的完整 prompt 原文
 * @param maxTurns 本次调用的 max_turns 软预算（可为空）
 * @param status 三段状态机状态
 * @param outcome 执行终态；{@link SubagentTaskStatus#OPEN} 时为 null
 * @param report 终态报告原文（COMPLETED 时的完整结果）
 * @param partialResult 失败/取消时保留的部分输出原文
 * @param error 失败或取消的说明文本
 * @param reminderTurn 已发出的软提醒轮次阈值（0 表示尚未提醒）
 * @param settledAt 执行终结时间；OPEN 时为 null
 * @param createdAt 创建时间（数据库时间戳）
 * @param updatedAt 最后更新时间（数据库时间戳）
 */
public record SubagentTask(
    UUID invocationId,
    UUID parentThreadId,
    UUID rootThreadId,
    UUID childSessionId,
    UUID childThreadId,
    UUID sourceHeadEntryId,
    String agent,
    String prompt,
    Integer maxTurns,
    SubagentTaskStatus status,
    Outcome outcome,
    String report,
    String partialResult,
    String error,
    long reminderTurn,
    Instant settledAt,
    Instant createdAt,
    Instant updatedAt) {

  public SubagentTask {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(childSessionId, "childSessionId");
    Objects.requireNonNull(childThreadId, "childThreadId");
    Objects.requireNonNull(sourceHeadEntryId, "sourceHeadEntryId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (agent == null || agent.isBlank()) {
      throw new IllegalArgumentException("agent must not be blank");
    }
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt must not be blank");
    }
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive when provided");
    }
    if (reminderTurn < 0) {
      throw new IllegalArgumentException("reminderTurn must not be negative");
    }
    // 与数据库 ck_harness_subagent_task_state_shape 同构：OPEN 无终态；SETTLED/DELIVERED 必须已有终态与终结时间。
    if (status == SubagentTaskStatus.OPEN) {
      if (outcome != null || settledAt != null) {
        throw new IllegalArgumentException("OPEN task must not carry a terminal outcome");
      }
    } else if (outcome == null || settledAt == null) {
      throw new IllegalArgumentException(
          "settled or delivered task must carry outcome and settledAt");
    }
  }

  /** 执行是否已终结（终态与报告已持久化）。 */
  public boolean settled() {
    return status != SubagentTaskStatus.OPEN;
  }

  /** 父通知是否已入队。 */
  public boolean delivered() {
    return status == SubagentTaskStatus.DELIVERED;
  }
}
