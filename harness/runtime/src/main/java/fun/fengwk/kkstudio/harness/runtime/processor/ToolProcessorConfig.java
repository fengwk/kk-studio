package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ToolProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 heartbeat；{@code retryPolicyProvider} 在每次 retryable 失败
 * retry 判定点现读（最终还受 binding 的 {@link fun.fengwk.kkstudio.harness.tool.ToolSideEffect} 约束，
 * NON_IDEMPOTENT 绝不自动重试）；{@code preflightFailureDelay} 是 preflight 抛异常 / 返回 null（确定无副作用）时的
 * reschedule 延迟；{@code admitFailureDelay} 是 Gateway admit 抛异常（肯定无副作用）或本地放弃（close）时的 reschedule 延迟。
 */
public record ToolProcessorConfig(
    ProcessorLeaseConfig leaseConfig,
    InvocationRetryPolicyProvider retryPolicyProvider,
    Duration preflightFailureDelay,
    Duration admitFailureDelay) {

  public ToolProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    retryPolicyProvider = Objects.requireNonNull(retryPolicyProvider, "retryPolicyProvider");
    preflightFailureDelay = requireMillisPositive(preflightFailureDelay, "preflightFailureDelay");
    admitFailureDelay = requireMillisPositive(admitFailureDelay, "admitFailureDelay");
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
