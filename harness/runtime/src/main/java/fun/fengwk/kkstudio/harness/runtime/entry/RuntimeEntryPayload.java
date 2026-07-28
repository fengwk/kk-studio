package fun.fengwk.kkstudio.harness.runtime.entry;

/**
 * 最终 Runtime Entry payload 族根类型。直接实现 {@link EntryPayload}，自报 {@link
 * fun.fengwk.kkstudio.harness.runtime.entry.EntryType}。
 */
public sealed interface RuntimeEntryPayload extends EntryPayload
    permits RootEntryPayload,
        MessageEntryPayload,
        CustomMessageEntryPayload,
        AssistantErrorEntryPayload,
        AssistantAbortedEntryPayload {}
