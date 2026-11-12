package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * ToolProcessor 的部署级配置。
 *
 * <p>{@code leaseConfigProvider} 在新 claim / execution 使用时读取 lease 与 heartbeat 配置；{@code
 * retryPolicyProvider} 在每次 retryable 失败 retry 判定点现读（最终还受 binding 的 {@link
 * fun.fengwk.kkstudio.harness.tool.ToolSideEffect} 约束， NON_IDEMPOTENT 绝不自动重试）；{@code
 * preflightFailureDelayProvider} 是 preflight 抛异常 / 返回 null（确定无副作用）时的 reschedule 延迟；{@code
 * admitFailureDelayProvider} 是 Gateway admit 抛异常（肯定无副作用）或本地放弃（close）时的 reschedule 延迟； {@code
 * reattachWindow} 是 RUNNING 远端工具挂接等待终态的超时上限，默认 10 分钟。
 */
public record ToolProcessorConfig(
    Supplier<ProcessorLeaseConfig> leaseConfigProvider,
    Supplier<InvocationRetryPolicy> retryPolicyProvider,
    Supplier<Duration> preflightFailureDelayProvider,
    Supplier<Duration> admitFailureDelayProvider,
    Duration reattachWindow) {

  public static final Duration DEFAULT_REATTACH_WINDOW = Duration.ofMinutes(10);

  public ToolProcessorConfig {
    leaseConfigProvider = Objects.requireNonNull(leaseConfigProvider, "leaseConfigProvider");
    retryPolicyProvider = Objects.requireNonNull(retryPolicyProvider, "retryPolicyProvider");
    preflightFailureDelayProvider =
        Objects.requireNonNull(preflightFailureDelayProvider, "preflightFailureDelayProvider");
    admitFailureDelayProvider =
        Objects.requireNonNull(admitFailureDelayProvider, "admitFailureDelayProvider");
    reattachWindow =
        HarnessStoreTime.requireWholeMillisecondDuration(reattachWindow, "reattachWindow");
  }
}
