package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchAdmission;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ModelProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 heartbeat；safe checkpoint 以有界批次聚合或在终态/重试事务中一次持久化，commit
 * 后发布；{@code retryPolicyProvider} 在每次 TRANSIENT 失败 retry 判定点现读；{@code dispatchBusyFallbackDelay} 是
 * Gateway start 抛异常（肯定未接受）时的 reschedule 延迟；{@code admissionDeferral} 是宿主拒绝新的对外执行时的 durable
 * 重排延迟（正延迟，绝不热循环）；{@code toolHistoryActionResolver} 在成功响应持久化前冻结 Tool-owned 历史 action（可为
 * null，表示全部回退）。ToolProcessor 的配置与之不同，因此本配置命名明确限定为 Model。
 */
public record ModelProcessorConfig(
    ProcessorLeaseConfig leaseConfig,
    InvocationRetryPolicyProvider retryPolicyProvider,
    Duration dispatchBusyFallbackDelay,
    Duration admissionDeferral,
    StreamFlushConfig streamFlushConfig,
    ToolHistoryActionResolver toolHistoryActionResolver) {

  public ModelProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    retryPolicyProvider = Objects.requireNonNull(retryPolicyProvider, "retryPolicyProvider");
    dispatchBusyFallbackDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(
            dispatchBusyFallbackDelay, "dispatchBusyFallbackDelay");
    admissionDeferral =
        HarnessStoreTime.requireWholeMillisecondDuration(admissionDeferral, "admissionDeferral");
    streamFlushConfig = Objects.requireNonNull(streamFlushConfig, "streamFlushConfig");
  }

  public ModelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      Duration dispatchBusyFallbackDelay,
      StreamFlushConfig streamFlushConfig,
      ToolHistoryActionResolver toolHistoryActionResolver) {
    this(
        leaseConfig,
        retryPolicyProvider,
        dispatchBusyFallbackDelay,
        WorkDispatchAdmission.DEFAULT_DEFERRAL,
        streamFlushConfig,
        toolHistoryActionResolver);
  }

  public ModelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      Duration dispatchBusyFallbackDelay,
      StreamFlushConfig streamFlushConfig) {
    this(
        leaseConfig,
        retryPolicyProvider,
        dispatchBusyFallbackDelay,
        WorkDispatchAdmission.DEFAULT_DEFERRAL,
        streamFlushConfig,
        null);
  }

  public ModelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      Duration dispatchBusyFallbackDelay) {
    this(
        leaseConfig,
        retryPolicyProvider,
        dispatchBusyFallbackDelay,
        WorkDispatchAdmission.DEFAULT_DEFERRAL,
        StreamFlushConfig.DEFAULT,
        null);
  }
}
