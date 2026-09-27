package fun.fengwk.kkstudio.platform.harness.task;

import java.util.Objects;
import java.util.UUID;

/**
 * 待插入的异步 task 委派事实（无时间戳）。
 *
 * <p>时间戳由数据库事务时间填充：接受、交付与提醒推进都以 PostgreSQL 的 {@code now()} 为唯一时间来源，避免宿主时钟与数据库时钟混用破坏 {@code
 * updated_at >= created_at} 约束，也让写入路径完全不绑定 Java 时间类型。
 *
 * @param invocationId task Tool invocation UUID（主键，稳定幂等身份）
 * @param parentThreadId 发起委派的父 Thread UUID
 * @param rootThreadId 委派树根 Thread UUID
 * @param childSessionId 子 Session UUID
 * @param childThreadId 子 Thread UUID
 * @param sourceHeadEntryId 本次执行边界（接受后子 Thread 的 head Entry）
 * @param agent 本次执行的 Agent 名
 * @param prompt 本次委派的完整 prompt 原文
 * @param maxTurns 本次调用的 max_turns 软预算（可为空）
 * @param status 投递状态（新建恒为 OPEN）
 * @param reminderTurn 已发出的软提醒轮次阈值（新建为 0）
 */
public record SubagentTaskDraft(
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
    long reminderTurn) {

  public SubagentTaskDraft {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(childSessionId, "childSessionId");
    Objects.requireNonNull(childThreadId, "childThreadId");
    Objects.requireNonNull(sourceHeadEntryId, "sourceHeadEntryId");
    Objects.requireNonNull(status, "status");
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
  }
}
