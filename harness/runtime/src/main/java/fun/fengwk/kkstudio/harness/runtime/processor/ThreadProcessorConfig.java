package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * ThreadProcessor 的部署级配置。
 *
 * <p>{@code resolveFailureDelayProvider} 每次 typed transient resolve 失败时读取事务外重排延迟；{@code
 * compactionProvider} 在每次压缩决策点现读 keep 与可选 fallback model（aiRuntime 配置 live 生效，无需重启）。Turn resolve
 * 已在调用方事务内完成，不存在解析期 lease/heartbeat。
 */
public record ThreadProcessorConfig(
    Supplier<Duration> resolveFailureDelayProvider, CompactionConfigProvider compactionProvider) {

  public ThreadProcessorConfig {
    resolveFailureDelayProvider =
        Objects.requireNonNull(resolveFailureDelayProvider, "resolveFailureDelayProvider");
    compactionProvider = Objects.requireNonNull(compactionProvider, "compactionProvider");
  }
}
