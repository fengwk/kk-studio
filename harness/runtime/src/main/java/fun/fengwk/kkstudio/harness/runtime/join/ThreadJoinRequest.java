package fun.fengwk.kkstudio.harness.runtime.join;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 子命令与 join 同事务 admission；空 parent 表示不投递父消息的 root completion ticket。
 *
 * <p>{@code maxDepth} 限制执行树深度，{@code maxConcurrentChildren} 限制同一父 Thread 的未完成 Join 数；{@code
 * maxConcurrentThreads} 是<b>全局</b>未完成子任务 Join（parentThreadId 非空且 terminalEntryId 为空，跨所有 root、不含
 * root ticket）上限，直接来自 task 的全局并发设置，不按树折算。
 */
public record ThreadJoinRequest(
    UUID invocationId,
    UUID parentThreadId,
    UUID expectedParentHeadEntryId,
    String requestHash,
    String agent,
    Integer maxTurns,
    int maxDepth,
    int maxConcurrentChildren,
    int maxConcurrentThreads) {

  public ThreadJoinRequest {
    Objects.requireNonNull(invocationId, "invocationId");
    if (parentThreadId != null && expectedParentHeadEntryId == null) {
      throw new IllegalArgumentException("parent head is required for task join");
    }
    if (parentThreadId == null && expectedParentHeadEntryId != null) {
      throw new IllegalArgumentException("root ticket has no parent head");
    }
    if (requestHash == null || !Pattern.matches("[0-9a-f]{64}", requestHash)) {
      throw new IllegalArgumentException("requestHash must be lowercase SHA-256 hex");
    }
    if (agent == null || agent.isBlank() || agent.length() > 256) {
      throw new IllegalArgumentException("agent must be nonblank and at most 256 characters");
    }
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    if (maxDepth < 1 || maxConcurrentChildren < 1 || maxConcurrentThreads < 1) {
      throw new IllegalArgumentException("admission limits must be positive");
    }
  }
}
