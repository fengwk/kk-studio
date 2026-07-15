package fun.fengwk.kkstudio.harness.tool.execution;

/**
 * Durable ownership information supplied by the Cloud worker. Environment tools deliberately do not
 * require it and remain compatible with the three argument execution request constructor.
 */
public record ToolExecutionContext(long invocationId, long runId) {
  public ToolExecutionContext {
    if (invocationId <= 0 || runId <= 0) {
      throw new IllegalArgumentException("invocation and run ids must be positive");
    }
  }
}
