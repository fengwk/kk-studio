package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;

/** 将一次 eligible command harvest 应用到 branch 事实后的不可变结果。 */
public record CommandHarvestResult(BranchSettings branchSettings) {

  public CommandHarvestResult {
    branchSettings = Objects.requireNonNull(branchSettings, "branchSettings");
  }
}
