package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;

import java.time.Instant;

/** Model / Tool processor 共享的 lease 工具：首次 Gateway 前确保 claim 有完整 lease margin。 */
final class ProcessorLeaseSupport {

  private ProcessorLeaseSupport() {}

  /** 在调用方已锁定 Work 行时，仅当现有租约早于 {@code now + leaseDuration} 才将其严格延展到该时点。 */
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
