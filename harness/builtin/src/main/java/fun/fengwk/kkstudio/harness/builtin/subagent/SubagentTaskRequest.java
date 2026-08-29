package fun.fengwk.kkstudio.harness.builtin.subagent;

import java.util.Objects;
import java.util.UUID;

/**
 * 委派给 SubagentRunner 执行的子 Agent 任务请求。
 *
 * @param invocationId 当前 invocation ID
 * @param threadId 当前 thread ID
 * @param prompt 任务 prompt 正文
 * @param subagentType 子 Agent 类型标识
 * @param maxTurns 最大 turns 限制（可为空）
 * @param sessionId 要恢复的子会话 ID（可为空）
 */
public record SubagentTaskRequest(
    UUID invocationId,
    UUID threadId,
    String prompt,
    String subagentType,
    Integer maxTurns,
    String sessionId) {

  public SubagentTaskRequest {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt must not be blank");
    }
    prompt = prompt.strip();
    if (subagentType == null || subagentType.isBlank()) {
      throw new IllegalArgumentException("subagentType must not be blank");
    }
    subagentType = subagentType.strip();
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive when provided");
    }
    if (sessionId != null) {
      if (sessionId.isBlank()) {
        throw new IllegalArgumentException("sessionId must not be blank when provided");
      }
      sessionId = sessionId.strip();
    }
  }
}
