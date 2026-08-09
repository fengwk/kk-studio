package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;

/**
 * Session 语义根 Entry；保存初始 branch settings 完整快照，以及可选的不可变子 Agent 委派归属。
 *
 * <p>普通用户 Session 的 {@code subagentContext} 为 null；子 Agent Session 由 task 平台工具创建并冻结该归属。
 */
public record RootPayload(BranchSettings settings, SubagentContext subagentContext)
    implements EntryPayload {

  /** 构造普通用户 Session ROOT。 */
  public RootPayload(BranchSettings settings) {
    this(settings, null);
  }

  public RootPayload {
    settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public EntryType type() {
    return EntryType.ROOT;
  }
}
