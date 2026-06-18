package fun.fengwk.kkstudio.agent.message;

/**
 * AgentMessage 表示一次模型调用上下文中的消息。
 *
 * @author fengwk
 */
public sealed interface AgentMessage permits AgentSystemMessage, AgentUserMessage, AgentAssistantMessage, AgentToolMessage {
}
