package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;

/** Immutable result of applying one eligible command harvest to branch and Thread policy facts. */
public record CommandHarvestResult(BranchSettings branchSettings, boolean yoloEnabled) {

  public CommandHarvestResult {
    branchSettings = Objects.requireNonNull(branchSettings, "branchSettings");
  }
}
