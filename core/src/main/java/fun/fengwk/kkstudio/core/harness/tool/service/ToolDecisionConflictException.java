package fun.fengwk.kkstudio.core.harness.tool.service;

/** 同一 Invocation 已持久化不同 permission decision。 */
public class ToolDecisionConflictException extends RuntimeException {
  public ToolDecisionConflictException(long invocationId) {
    super("conflicting permission decision for invocation: " + invocationId);
  }
}
