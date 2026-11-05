package fun.fengwk.kkstudio.harness.spi.work;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;

import java.time.Duration;

/**
 * 通用 processor lease 参数：claim 的有效期与本地 heartbeat 节奏。
 *
 * <p>heartbeat 只 renew 当前 Work 的 lease，不触碰 Thread / Invocation / version；interval 必须为正且严格小于
 * leaseDuration，保证两次 heartbeat 之间 lease 不会过期。
 */
public record ProcessorLeaseConfig(Duration leaseDuration, Duration heartbeatInterval) {

  public ProcessorLeaseConfig {
    leaseDuration =
        HarnessStoreTime.requireWholeMillisecondDuration(leaseDuration, "leaseDuration");
    heartbeatInterval =
        HarnessStoreTime.requireWholeMillisecondDuration(heartbeatInterval, "heartbeatInterval");
    if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
      throw new IllegalArgumentException("heartbeatInterval must be less than leaseDuration");
    }
  }
}
