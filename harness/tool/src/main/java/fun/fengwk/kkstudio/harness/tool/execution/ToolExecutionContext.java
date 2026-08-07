package fun.fengwk.kkstudio.harness.tool.execution;

/** 提供给 Platform Tool 执行的 durable invocation/thread 归属。 */
public record ToolExecutionContext(long invocationId, long threadId) {
  public ToolExecutionContext {
    if (invocationId <= 0 || threadId <= 0) {
      throw new IllegalArgumentException("invocation and thread ids must be positive");
    }
  }
}
