package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * ToolProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 heartbeat；{@code retryPolicy} 是 retryable 失败自动重试策略（最终还受
 * binding 的 {@link fun.fengwk.kkstudio.harness.tool.ToolSideEffect} 约束，NON_IDEMPOTENT 绝不自动重试）；
 * {@code preflightFailureDelay} 是 preflight 抛异常 / 返回 null（确定无副作用）时的 reschedule 延迟； {@code
 * dispatchBusyFallbackDelay} 是 Gateway start 抛异常（肯定未接受）时的 reschedule 延迟。
 */
public record ToolProcessorConfig(
    ProcessorLeaseConfig leaseConfig,
    InvocationRetryPolicy retryPolicy,
    Duration preflightFailureDelay,
    Duration dispatchBusyFallbackDelay) {

  public ToolProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    preflightFailureDelay = requireMillisPositive(preflightFailureDelay, "preflightFailureDelay");
    dispatchBusyFallbackDelay =
        requireMillisPositive(dispatchBusyFallbackDelay, "dispatchBusyFallbackDelay");
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
      throw new IllegalArgumentException(name + " must be at least one millisecond");
    }
    return value;
  }
}
