package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ThreadProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 Resolver 外部解析期间的 heartbeat；{@code stepLimit} 是单次 claim
 * 处理的有界步骤上限（每步一个短事务；达到上限后按 {@code resolveFailureDelay} 重排 THREAD Work，绝不静默丢弃）；{@code
 * resolveFailureDelay} 是 Resolver 异常 / null、heartbeat 调度失败与 step limit 共用的单一正失败延迟。
 */
public record ThreadProcessorConfig(
    ProcessorLeaseConfig leaseConfig, int stepLimit, Duration resolveFailureDelay) {

  public ThreadProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    if (stepLimit <= 0) {
      throw new IllegalArgumentException("stepLimit must be positive");
    }
    resolveFailureDelay = requireMillisPositive(resolveFailureDelay, "resolveFailureDelay");
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
