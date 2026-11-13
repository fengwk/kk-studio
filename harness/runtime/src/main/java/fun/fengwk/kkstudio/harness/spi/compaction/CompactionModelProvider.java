package fun.fengwk.kkstudio.harness.spi.compaction;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;

/** 向 Turn 解析提供每次决策现读的显式压缩模型选择；{@code null} 表示沿用对话当前模型与 variant。 */
@FunctionalInterface
public interface CompactionModelProvider {

  /** 返回当前配置的压缩模型选择；未配置时返回 {@code null}。 */
  ModelSelection compactionModel();
}
