package fun.fengwk.kkstudio.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

/**
 * ToolCallMapper 负责在持久化工具调用结构与 LangChain4j 工具调用结构之间转换。
 *
 * @author fengwk
 */
public class ToolCallMapper {

    public ToolCall from(ToolExecutionRequest request) {
        if (request == null) {
            return null;
        }
        ToolCall toolCall = new ToolCall();
        toolCall.setToolCallId(request.id());
        toolCall.setToolName(request.name());
        toolCall.setArguments(request.arguments());
        return toolCall;
    }

    public ToolExecutionRequest toToolExecutionRequest(ToolCall toolCall) {
        if (toolCall == null) {
            return null;
        }
        return ToolExecutionRequest.builder()
            .id(toolCall.getToolCallId())
            .name(toolCall.getToolName())
            .arguments(toolCall.getArguments())
            .build();
    }

}
