package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ModelProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 heartbeat；safe checkpoint 在每个 text/thinking delta
 * 发布前同步持久化；{@code retryPolicy} 是 TRANSIENT 失败自动重试策略；{@code dispatchBusyFallbackDelay} 是 Gateway
 * start 抛异常（肯定未接受）时的 reschedule 延迟。ToolProcessor 的配置与之不同，因此本配置命名明确限定为 Model。
 */
public record ModelProcessorConfig(
    ProcessorLeaseConfig leaseConfig,
    InvocationRetryPolicy retryPolicy,
    Duration dispatchBusyFallbackDelay) {

  public ModelProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    dispatchBusyFallbackDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(
            dispatchBusyFallbackDelay, "dispatchBusyFallbackDelay");
  }
}
