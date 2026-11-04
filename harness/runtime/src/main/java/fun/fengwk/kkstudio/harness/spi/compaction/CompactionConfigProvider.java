package fun.fengwk.kkstudio.harness.spi.compaction;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;

/** 向 ThreadProcessor 提供每次压缩决策现读的 {@link CompactionConfig}。 */
@FunctionalInterface
public interface CompactionConfigProvider {

  /** 返回当前生效的压缩配置；每次决策点调用。 */
  CompactionConfig compactionConfig();
}
