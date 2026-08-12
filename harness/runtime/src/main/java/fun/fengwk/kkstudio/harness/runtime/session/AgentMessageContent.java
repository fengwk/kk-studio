package fun.fengwk.kkstudio.harness.runtime.session;

/**
 * AgentMessage 的无 Provider 绑定内容单元。
 *
 * <p>durable 类型（可持久化/编解码）：text / thinking / json / tool_call / tool_result / resource（共 6 类）。
 * 瞬时类型（仅内存 / 请求形态，任何 durable codec 拒绝）：attachment（入队 wire 形态，消费后物化为 resource）、 image / audio /
 * video（Provider attempt 投影，绝不进入持久化 JSON）。
 */
public sealed interface AgentMessageContent
    permits TextMessageContent,
        ImageMessageContent,
        AudioMessageContent,
        VideoMessageContent,
        ThinkingMessageContent,
        JsonMessageContent,
        ToolCallMessageContent,
        ToolResultMessageContent,
        ResourceMessageContent,
        AttachmentMessageContent {}
