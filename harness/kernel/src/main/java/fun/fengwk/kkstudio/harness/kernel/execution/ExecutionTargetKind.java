package fun.fengwk.kkstudio.harness.kernel.execution;

/** 唯一标识执行主体（{@link ExecutionTarget}）的种类枚举。 */
public enum ExecutionTargetKind {
  THREAD,
  MODEL_INVOCATION,
  TOOL_INVOCATION
}
