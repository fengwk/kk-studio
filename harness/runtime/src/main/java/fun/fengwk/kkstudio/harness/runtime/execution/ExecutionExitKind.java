package fun.fengwk.kkstudio.harness.runtime.execution;

/** 一次具体执行代次的种类。远端调用不是第四种调度 activity，只是挂在本地 Tool 代次上的独立事实。 */
public enum ExecutionExitKind {
  /** Studio 发起的一次 Model body。 */
  MODEL,
  /** Studio 发起的一次 Tool body。 */
  TOOL,
  /** 一次实际 transport invocation；必须关联本地 Tool 代次。 */
  REMOTE
}
