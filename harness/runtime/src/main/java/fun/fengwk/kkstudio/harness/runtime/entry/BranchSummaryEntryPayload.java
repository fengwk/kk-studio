package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.kernel.session.EntryType;

/** Branch 路径结束处的摘要 Entry。 */
public record BranchSummaryEntryPayload(String summary) implements RuntimeEntryPayload {

  public BranchSummaryEntryPayload {
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.BRANCH_SUMMARY;
  }
}
