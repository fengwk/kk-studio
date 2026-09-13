package fun.fengwk.kkstudio.harness.runtime.port;

import java.util.Objects;
import java.util.UUID;

/**
 * Tool 结果物化上下文：显式携带会话、线程、调用标识与工具名称，禁止从 Session 反推。
 *
 * @param sessionId 会话标识
 * @param threadId 线程标识
 * @param invocationId 工具调用标识
 * @param toolName 工具名称
 */
public record ToolResultHistoryContext(
    UUID sessionId, UUID threadId, UUID invocationId, String toolName) {

  public ToolResultHistoryContext {
    sessionId = Objects.requireNonNull(sessionId, "sessionId");
    threadId = Objects.requireNonNull(threadId, "threadId");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    toolName = Objects.requireNonNull(toolName, "toolName");
    if (toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
  }
}
