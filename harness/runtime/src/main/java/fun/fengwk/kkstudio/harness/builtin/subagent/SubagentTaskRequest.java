package fun.fengwk.kkstudio.harness.builtin.subagent;

import java.util.Objects;
import java.util.UUID;

/**
 * 委派给 {@link SubagentRunner} 接受的 Subagent 任务请求。
 *
 * <p>一次 task Tool invocation 对应一个请求：{@code invocationId} 是本次委派的稳定身份，Runner 必须以它为幂等键，保证同一 次 Tool
 * 调用重试返回同一个子 Thread，而不是重复开启执行。
 *
 * @param invocationId 当前 task Tool invocation 的 UUID（稳定幂等身份）
 * @param parentThreadId 发起委派的父 Thread UUID
 * @param prompt 本次任务的完整 prompt 正文（父调用的权威指令，交付结果时按持久事实回读）
 * @param subagentType 目标 Agent（subagent_type）名
 * @param maxTurns 本次调用的 max_turns 软预算（可为空，表示使用当前 policy 默认）
 * @param resumeThreadId 要继续的既有子 Thread UUID（可为空；非空时表示在既有历史上继续，可切换 Agent）
 */
public record SubagentTaskRequest(
    UUID invocationId,
    UUID parentThreadId,
    String prompt,
    String subagentType,
    Integer maxTurns,
    UUID resumeThreadId) {

  public SubagentTaskRequest {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt must not be blank");
    }
    if (subagentType == null || subagentType.isBlank()) {
      throw new IllegalArgumentException("subagentType must not be blank");
    }
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive when provided");
    }
  }
}
