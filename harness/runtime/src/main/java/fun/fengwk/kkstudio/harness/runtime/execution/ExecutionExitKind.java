package fun.fengwk.kkstudio.harness.runtime.execution;

/** 一次具体执行代次的种类。远端调用不是额外调度 activity，只是挂在本地 TOOL/AGENT 代次上的独立事实。 */
public enum ExecutionExitKind {
  /** Studio 发起的一次 Model body。 */
  MODEL,
  /** Studio 发起的一次 Tool body。 */
  TOOL,
  /** Studio 发起的一次 AGENT 输入周期 body。 */
  AGENT,
  /** 一次实际 transport invocation；必须关联本地 TOOL 或 AGENT 代次。 */
  REMOTE
}
