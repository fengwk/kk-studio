package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.util.Objects;

/** Driver 解析出的压缩执行计划；Runtime 只消费中立选择与预算并创建压缩子 Thread。 */
public record CompactionResolution(
    ModelSelection executionModel,
    long outputBudget,
    int contextWindow,
    int maxOutputTokens,
    BranchSettings childSettings) {

  public CompactionResolution {
    executionModel = Objects.requireNonNull(executionModel, "executionModel");
    if (outputBudget <= 0L) {
      throw new IllegalArgumentException("outputBudget must be positive");
    }
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    childSettings = Objects.requireNonNull(childSettings, "childSettings");
  }
}
