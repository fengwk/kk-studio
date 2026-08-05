package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ModelProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 heartbeat；{@code checkpointFlushInterval} 控制 safe
 * checkpoint 的节流写入间隔（首个 safe delta 立即 flush，之后只有达到间隔才写，terminal success 总是 flush 完整快照）； {@code
 * retryPolicy} 是 TRANSIENT 失败自动重试策略；{@code dispatchBusyFallbackDelay} 是 Gateway start 抛异常（肯定未 接受）时的
 * reschedule 延迟。ToolProcessor 的配置与之不同，因此本配置命名明确限定为 Model。
 */
public record ModelProcessorConfig(
    ProcessorLeaseConfig leaseConfig,
    Duration checkpointFlushInterval,
    InvocationRetryPolicy retryPolicy,
    Duration dispatchBusyFallbackDelay) {

  public ModelProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    checkpointFlushInterval =
        requireMillisPositive(checkpointFlushInterval, "checkpointFlushInterval");
    retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    dispatchBusyFallbackDelay =
        requireMillisPositive(dispatchBusyFallbackDelay, "dispatchBusyFallbackDelay");
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
