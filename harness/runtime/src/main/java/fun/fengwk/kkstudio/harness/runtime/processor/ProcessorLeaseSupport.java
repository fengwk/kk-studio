package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;

import java.time.Instant;

/** Model / Tool processor 共享的 lease 工具：首次 Gateway 前确保 claim 有完整 lease margin。 */
final class ProcessorLeaseSupport {

  private ProcessorLeaseSupport() {}

  /**
   * 只要当前 leaseUntil 早于 {@code now + leaseDuration} 就 renew 到 {@code now + leaseDuration} （相等 / 更晚不
   * renew——renew 要求严格延展，等值会违反）。调用方必须已通过 {@code lockClaimedWork} 锁住 Work 行。
   */
  static void ensureLeaseMargin(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      Work work,
      ProcessorLeaseConfig leaseConfig,
      Instant now) {
    Instant target = now.plus(leaseConfig.leaseDuration());
    if (!work.leaseUntil().isBefore(target)) {
      return;
    }
    tx.renewWork(claim, now, target);
  }
}
