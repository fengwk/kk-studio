package fun.fengwk.kkstudio.harness.api.execution;

/** 一次具体执行代次的种类。远端调用不是额外调度 activity，只是挂在本地 TOOL/DRIVER 代次上的独立事实。 */
public enum ExecutionExitKind {
  /** Studio 发起的一次 Driver body。 */
  DRIVER,
  /** Studio 发起的一次 Tool body。 */
  TOOL,
  /** 一次实际 transport invocation；必须关联本地 TOOL 或 DRIVER 代次。 */
  REMOTE
}
