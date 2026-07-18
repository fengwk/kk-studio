package fun.fengwk.kkstudio.harness.tool.execution;

/**
 * Durable ownership information supplied by the tool worker. Environment tools deliberately do not
 * require it and remain compatible with the three argument execution request constructor.
 */
public record ToolExecutionContext(long invocationId, long threadId) {
  public ToolExecutionContext {
    if (invocationId <= 0 || threadId <= 0) {
      throw new IllegalArgumentException("invocation and thread ids must be positive");
    }
  }
}
