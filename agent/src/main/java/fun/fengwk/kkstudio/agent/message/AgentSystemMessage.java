package fun.fengwk.kkstudio.agent.message;

/**
 * 系统提示消息。
 *
 * @author fengwk
 */
public record AgentSystemMessage(String text) implements AgentMessage {}
