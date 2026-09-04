package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

/** Tool approval decision 值。 */
public enum ToolApprovalDecision {
  /** 审批通过，调用恢复为 READY 并可重新进入调度。 */
  ALLOWED,

  /** 审批拒绝，调用携带 DENIED 错误收敛为 FAILED。 */
  DENIED
}
