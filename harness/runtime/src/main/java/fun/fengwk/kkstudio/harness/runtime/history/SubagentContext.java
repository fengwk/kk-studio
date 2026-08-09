package fun.fengwk.kkstudio.harness.runtime.history;

/**
 * 子 Agent Session ROOT 上冻结的委派归属。
 *
 * <p>{@code taskInvocationId} 把子 Session 关联到父 Thread 的 durable task ToolInvocation；{@code
 * rootThreadId} 在整棵委派树中保持不变，{@code depth} 以普通根 Thread 为 1，因此子 Session 从 2 开始。
 */
public record SubagentContext(
    long parentThreadId, long rootThreadId, long taskInvocationId, int depth) {

  public SubagentContext {
    if (parentThreadId <= 0 || rootThreadId <= 0 || taskInvocationId <= 0) {
      throw new IllegalArgumentException(
          "subagent parent/root thread and task invocation ids must be positive");
    }
    if (depth < 2) {
      throw new IllegalArgumentException("subagent depth must be >= 2");
    }
  }
}
