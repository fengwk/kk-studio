package fun.fengwk.kkstudio.agent.provider;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

/**
 * AssistantResponseHandler 表示 assistant 流式回调协议。
 *
 * @author fengwk
 */
public interface AssistantResponseHandler {

    /**
     * 接收 assistant 文本增量。
     */
    void onTextDelta(String textDelta, AssistantResponseHandle handle);

    /**
     * 接收 assistant thinking 增量。
     */
    void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle);

    /**
     * 接收 tool call 增量。
     */
    void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle);

    /**
     * 接收单个 tool call 的最终完整结果。
     */
    void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle);

    /**
     * 接收 assistant 完整结束结果。
     */
    void onComplete(AssistantResponse response, AssistantResponseHandle handle);

    /**
     * 接收 assistant 异常结束结果。
     */
    void onError(Throwable error, AssistantResponseHandle handle);

}
