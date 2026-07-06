package fun.fengwk.kkstudio.agent.message;

/**
 * 用户输入消息。
 *
 * @author fengwk
 */
public record AgentUserMessage(String text) implements AgentMessage {}
