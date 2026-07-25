package fun.fengwk.kkstudio.harness.model.provider;

/** Provider 无关的消息内容单元。 */
public sealed interface ProviderContentBlock
    permits ProviderTextBlock,
        ProviderImageBlock,
        ProviderAudioBlock,
        ProviderVideoBlock,
        ProviderThinkingBlock,
        ProviderJsonBlock,
        ProviderToolCallBlock,
        ProviderToolResultBlock {}
