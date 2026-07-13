package fun.fengwk.kkstudio.harness.runtime.session;

public record BranchSummaryEntryPayload(String summary) implements SessionEntryPayload {
  public BranchSummaryEntryPayload {
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.BRANCH_SUMMARY;
  }
}
