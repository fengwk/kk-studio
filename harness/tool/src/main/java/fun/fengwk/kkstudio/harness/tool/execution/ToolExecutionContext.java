package fun.fengwk.kkstudio.harness.tool.execution;

/** Durable invocation/thread ownership supplied to Platform Tool executions. */
public record ToolExecutionContext(long invocationId, long threadId) {
  public ToolExecutionContext {
    if (invocationId <= 0 || threadId <= 0) {
      throw new IllegalArgumentException("invocation and thread ids must be positive");
    }
  }
}
