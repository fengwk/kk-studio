package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ThreadProcessor 的部署级配置。
 *
 * <p>{@code resolveFailureDelay} 是 typed transient resolve 失败的事务外重排延迟；{@code compactionProvider}
 * 在每次压缩决策点现读 keep 与可选 fallback model（aiRuntime 配置 live 生效，无需重启）。Turn resolve 已在调用方事务内完成，不存在解析期
 * lease/heartbeat。
 */
public record ThreadProcessorConfig(
    Duration resolveFailureDelay, CompactionConfigProvider compactionProvider) {

  public ThreadProcessorConfig {
    resolveFailureDelay = requireMillisPositive(resolveFailureDelay, "resolveFailureDelay");
    compactionProvider = Objects.requireNonNull(compactionProvider, "compactionProvider");
  }

  private static Duration requireMillisPositive(Duration value, String name) {
    return HarnessStoreTime.requireWholeMillisecondDuration(value, name);
  }
}
