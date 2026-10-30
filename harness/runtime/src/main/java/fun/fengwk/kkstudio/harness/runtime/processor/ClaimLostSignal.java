package fun.fengwk.kkstudio.harness.runtime.processor;

/**
 * 已认领执行在事务内最终所有权围栏失败时抛出的回滚信号。
 *
 * <p>事务内的最终 fence（{@code lockClaimedWork}）失败说明 claim 已被其它执行者接管或过期回收，此刻写入的任何部分事实都必须整体回滚。抛出本信号使 Store
 * 事务控制流回滚，由 Processor 映射为 {@code LOST_OWNERSHIP}；它不表达可诊断的业务失败，因此不携带堆栈。
 */
public final class ClaimLostSignal extends RuntimeException {

  public ClaimLostSignal() {
    super("claimed work lost at final fence", null, false, false);
  }
}
