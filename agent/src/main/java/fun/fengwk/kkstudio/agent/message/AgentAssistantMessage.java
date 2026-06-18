package fun.fengwk.kkstudio.agent.message;

import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

import java.util.List;

/**
 * Assistant 回复消息。
 *
 * @author fengwk
 */
public record AgentAssistantMessage(String text, String thinking, List<ToolCall> toolCalls) implements AgentMessage {

    public AgentAssistantMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

}
