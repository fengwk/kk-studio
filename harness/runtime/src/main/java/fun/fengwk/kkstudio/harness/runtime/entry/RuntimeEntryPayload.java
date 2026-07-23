package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.kernel.session.EntryPayload;

/**
 * 最终 Runtime Entry payload 族根类型。直接实现 Kernel {@link EntryPayload}，自报 {@link
 * fun.fengwk.kkstudio.harness.kernel.session.EntryType}。
 */
public sealed interface RuntimeEntryPayload extends EntryPayload
    permits RootEntryPayload,
        MessageEntryPayload,
        CustomMessageEntryPayload,
        CompactionEntryPayload,
        AssistantErrorEntryPayload,
        LabelEntryPayload,
        BranchSummaryEntryPayload {}
