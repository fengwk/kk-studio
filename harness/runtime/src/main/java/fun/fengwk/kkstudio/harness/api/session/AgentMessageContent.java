package fun.fengwk.kkstudio.harness.api.session;

/**
 * AgentMessage 的有序内容块；assistant 块可携带对应 Provider 的原生字段。
 *
 * <p>durable 类型（可持久化/编解码）：text / thinking / redacted_thinking / json / tool_call / tool_result /
 * resource。 瞬时类型（仅内存 / 请求形态，任何 durable codec 拒绝）：attachment（入队 wire 形态，消费后物化为 resource）、 image /
 * audio / video（Provider attempt 投影，绝不进入持久化 JSON）。
 */
public sealed interface AgentMessageContent
    permits TextMessageContent,
        ImageMessageContent,
        AudioMessageContent,
        VideoMessageContent,
        NativeAssistantBlockContent,
        ThinkingMessageContent,
        RedactedThinkingMessageContent,
        JsonMessageContent,
        ToolCallMessageContent,
        ToolResultMessageContent,
        ResourceMessageContent,
        AttachmentMessageContent {}
