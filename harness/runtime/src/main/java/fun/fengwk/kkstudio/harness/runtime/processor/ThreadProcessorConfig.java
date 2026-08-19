package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ThreadProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 Resolver 外部解析期间的 heartbeat；{@code resolveFailureDelay} 是
 * Resolver 异常 / null 与 heartbeat 调度失败共用的单一正失败延迟；{@code compaction} 是自动对话压缩的部署开关与 token 预算。
 */
public record ThreadProcessorConfig(
    ProcessorLeaseConfig leaseConfig, Duration resolveFailureDelay, CompactionConfig compaction) {

  public ThreadProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    resolveFailureDelay = requireMillisPositive(resolveFailureDelay, "resolveFailureDelay");
    compaction = Objects.requireNonNull(compaction, "compaction");
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
