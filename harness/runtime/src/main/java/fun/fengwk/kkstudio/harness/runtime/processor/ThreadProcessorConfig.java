package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ThreadProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 Resolver 外部解析期间的 heartbeat；{@code resolveFailureDelay} 是
 * Resolver 异常 / null 与 heartbeat 调度失败共用的单一正失败延迟；{@code compactionProvider} 在每次压缩决策点现读 keep 与可选
 * fallback model（aiRuntime 配置 live 生效，无需重启）。
 */
public record ThreadProcessorConfig(
    ProcessorLeaseConfig leaseConfig,
    Duration resolveFailureDelay,
    CompactionConfigProvider compactionProvider) {

  public ThreadProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    resolveFailureDelay = requireMillisPositive(resolveFailureDelay, "resolveFailureDelay");
    compactionProvider = Objects.requireNonNull(compactionProvider, "compactionProvider");
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
