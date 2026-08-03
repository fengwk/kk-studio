package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;

/** Session 语义根 Entry；保存该 Session Entry Tree 的初始 branch settings 完整快照。 */
public record RootPayload(BranchSettings settings) implements EntryPayload {

  public RootPayload {
    settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public EntryType type() {
    return EntryType.ROOT;
  }
}
