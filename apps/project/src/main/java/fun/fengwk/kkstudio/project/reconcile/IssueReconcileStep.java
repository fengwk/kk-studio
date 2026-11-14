package fun.fengwk.kkstudio.project.reconcile;

import java.time.Duration;

/** 一次调谐动作的决策：结果类型、是否保留 mailbox 继续轮询，以及下次检查延迟。 */
record IssueReconcileStep(IssueReconcileOutcome outcome, boolean keepPolling, Duration delay) {

  /** 归还 lease、保留 mailbox 并按延迟重新检查。 */
  static IssueReconcileStep poll(Duration delay, IssueReconcileOutcome outcome) {
    return new IssueReconcileStep(outcome, true, delay);
  }

  /** 完成当前 wake：没有待推进事实时结束 claim。 */
  static IssueReconcileStep converged(IssueReconcileOutcome outcome) {
    return new IssueReconcileStep(outcome, false, Duration.ZERO);
  }
}
