package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;
import java.util.UUID;

/**
 * Subagent Session ROOT 上冻结的委派归属。
 *
 * <p>{@code taskInvocationId} 把 Subagent Session 关联到父 Thread 的持久化 task ToolInvocation；{@code
 * rootThreadId} 在整棵委派树中保持不变，{@code depth} 以普通根 Thread 为 1，因此 Subagent Session 从 2 开始。
 */
public record SubagentContext(
    UUID parentThreadId, UUID rootThreadId, UUID taskInvocationId, int depth) {

  public SubagentContext {
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(taskInvocationId, "taskInvocationId");
    if (depth < 2) {
      throw new IllegalArgumentException("subagent depth must be >= 2");
    }
  }
}
