package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.time.Instant;

/**
 * Model / Tool processor 共享的 lease 工具：首次 Gateway 前确保 claim 有完整 lease margin。
 *
 * <p>是否存在 margin 缺口由 Store 在权威时间域内判定：{@code renewWork} 只在现有 lease 尚未覆盖权威时间 + leaseDuration 时延展，
 * 因此这里不做任何 JVM 时钟比较，避免节点时钟偏差导致错误的续租或缺租。
 */
final class ProcessorLeaseSupport {

  private ProcessorLeaseSupport() {}

  /** 在调用方已锁定 Work 行时确保现有租约至少覆盖权威时间 + leaseDuration。 */
  static void ensureLeaseMargin(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ProcessorLeaseConfig leaseConfig,
      Instant now) {
    tx.renewWork(claim, now, leaseConfig.leaseDuration());
  }
}
