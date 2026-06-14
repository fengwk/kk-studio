package fun.fengwk.kkstudio.agent.provider;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

/**
 * AssistantResponseHandler 表示 assistant 流式回调协议。
 *
 * @author fengwk
 */
public interface AssistantResponseHandler {

    void onTextDelta(String textDelta, AssistantResponseHandle handle);

    void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle);

    void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle);

    void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle);

    void onComplete(AssistantResponse response, AssistantResponseHandle handle);

    void onError(Throwable error, AssistantResponseHandle handle);

}
