package fun.fengwk.kkstudio.harness.runtime.session;

/** Session Entry 的完整、封闭 payload 集合。 */
public sealed interface SessionEntryPayload
    permits MessageEntryPayload,
        AgentSnapshotEntryPayload,
        ModelChangeEntryPayload,
        ToolsetChangeEntryPayload,
        CompactionEntryPayload,
        BranchSummaryEntryPayload,
        CustomEntryPayload,
        CustomMessageEntryPayload,
        LabelEntryPayload {
  SessionEntryType type();
}
