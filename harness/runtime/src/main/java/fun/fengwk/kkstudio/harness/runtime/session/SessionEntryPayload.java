package fun.fengwk.kkstudio.harness.runtime.session;

/** Session Entry 的完整、封闭 payload 集合。 */
public sealed interface SessionEntryPayload
    permits RootEntryPayload,
        MessageEntryPayload,
        AgentChangeEntryPayload,
        ModelChangeEntryPayload,
        CompactionEntryPayload,
        BranchSummaryEntryPayload,
        CustomEntryPayload,
        CustomMessageEntryPayload,
        LabelEntryPayload,
        AssistantErrorEntryPayload {
  SessionEntryType type();
}
