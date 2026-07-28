package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

/**
 * apply Model / apply Tool / harvest 事务的有限结果。
 *
 * <p>仅 {@link #PROGRESSED} 与 {@link #LOST_OWNERSHIP} 两种情形；意外 RuntimeException 经 best-effort release
 * 后重新抛出。
 */
public enum ApplyOutcome {
  /** apply/harvest 已推进 durable facts。 */
  PROGRESSED,

  /** fencing 校验失败；调用方必须停止当前 activation。 */
  LOST_OWNERSHIP
}
