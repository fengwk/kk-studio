package fun.fengwk.kkstudio.harness.runtime.reconcile;

/**
 * suspendAndRecheck 事务的有限结果。
 *
 * <p>{@link #SUSPENDED} 表示期望的 durable blocker 仍未解决且 lease 已在事务内释放；{@link #WORK_AVAILABLE} 表示
 * sibling 已终态或新 work 到达，调用方在不释放 lease 的前提下继续循环；{@link #LOST_OWNERSHIP} 表示 fencing 失败。意外
 * RuntimeException 经 best-effort release 后重新抛出。
 */
public enum SuspendOutcome {
  /** 期望的 durable blocker 仍未解决；Reconciler 返回调用时传入的 expectedBlocker。 */
  SUSPENDED,

  /** sibling 已终态或新 work 到达；lease 仍由调用方持有。 */
  WORK_AVAILABLE,

  /** fencing 校验失败。 */
  LOST_OWNERSHIP
}
