package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;

/** claim 的 final fence：在事务末尾重锁确认仍持有该 claim，再 complete 对应 Work。 */
final class WorkClaims {

  private WorkClaims() {}

  /**
   * final fence 最后执行：损失抛内部信号整事务回滚，绝无带 mutation 的 LOST 提交。
   *
   * @throws ClaimLostSignal claim 已丢失
   */
  static void complete(HarnessStore.Transaction tx, ClaimedWork claim) {
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().complete(claim);
  }
}
