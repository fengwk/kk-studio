package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

/**
 * quiesceAndRecheck 事务的有限结果。
 *
 * <p>{@link #QUIESCENT} 表示 recheck 确认无可推进 work 且 lease 已在事务内释放；{@link #WORK_AVAILABLE} 表示 enqueue 或
 * sibling terminal 已在 quiesce 临界窗口内提交，调用方继续循环且不释放 lease；{@link #LOST_OWNERSHIP} 表示 fencing 失败。意外
 * RuntimeException 经 best-effort release 后重新抛出。
 */
public enum QuiesceOutcome {
  /** 事务内已释放 lease；Reconciler 返回 StepResult.Quiescent。 */
  QUIESCENT,

  /** 新 work 到达；lease 仍由调用方持有。 */
  WORK_AVAILABLE,

  /** fencing 校验失败。 */
  LOST_OWNERSHIP
}
