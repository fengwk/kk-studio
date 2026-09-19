package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.List;
import java.util.Objects;

/**
 * 将一次 eligible command harvest 应用到 branch 事实后的不可变结果。
 *
 * <p>{@code changes} 按 command 顺序记录每个真正改变 settings 的 SET_* 命令及其应用后的完整快照；no-op 设置不出现，因此调用方
 * 不会为未发生的变化注入提醒。
 */
public record CommandHarvestResult(BranchSettings branchSettings, List<SettingsChange> changes) {

  public CommandHarvestResult {
    branchSettings = Objects.requireNonNull(branchSettings, "branchSettings");
    changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
  }

  /** 一次生效的设置变更新快照：{@code type} 是触发的 SET_* 命令类型。 */
  public record SettingsChange(ThreadCommandType type, BranchSettings settings) {
    public SettingsChange {
      type = Objects.requireNonNull(type, "type");
      settings = Objects.requireNonNull(settings, "settings");
    }
  }
}
