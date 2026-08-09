package fun.fengwk.kkstudio.harness.runtime.session;

/** 持久化 AgentMessage 的无 Provider 绑定内容单元。 */
public sealed interface AgentMessageContent
    permits TextMessageContent,
        ImageMessageContent,
        AudioMessageContent,
        VideoMessageContent,
        ThinkingMessageContent,
        JsonMessageContent,
        ToolCallMessageContent,
        ToolResultMessageContent,
        ResourceMessageContent {}
