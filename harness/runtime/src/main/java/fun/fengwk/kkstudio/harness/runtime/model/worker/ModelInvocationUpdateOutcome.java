package fun.fengwk.kkstudio.harness.runtime.model.worker;

/**
 * 单个 ModelInvocation mutation 的有限 fencing 结果。
 *
 * <p>{@link #APPLIED} 对 terminal mutation 还表示适配器已在同一事务标记 owning Thread runnable；对 retry mutation
 * 表示已写入 {@code RETRY_WAIT}。{@link #LOST_OWNERSHIP} 同时涵盖过期 lease、epoch 已变化、终态已写入和重复
 * callback，调用方只能停止本地 handle。适配器的 CAS 必须比较 execution epoch、attempt、worker token 与 lease 有效期。
 */
public enum ModelInvocationUpdateOutcome {
  APPLIED,
  LOST_OWNERSHIP
}
