package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Objects;

/** 已完成、可持久化并可投影到 Provider 的语义消息。 */
public record AgentMessage(AgentMessageRole role, List<AgentMessageContent> contents) {
  public AgentMessage {
    role = Objects.requireNonNull(role, "role");
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
    if (contents.isEmpty()) {
      throw new IllegalArgumentException("contents must not be empty");
    }
    boolean hasCall = contents.stream().anyMatch(ToolCallMessageContent.class::isInstance);
    boolean hasResult = contents.stream().anyMatch(ToolResultMessageContent.class::isInstance);
    if (hasCall && role != AgentMessageRole.ASSISTANT) {
      throw new IllegalArgumentException("tool calls are only allowed for assistant messages");
    }
    if (hasResult && role != AgentMessageRole.TOOL) {
      throw new IllegalArgumentException("tool results are only allowed for tool messages");
    }
    if (role == AgentMessageRole.TOOL
        && (contents.size() != 1 || !(contents.get(0) instanceof ToolResultMessageContent))) {
      throw new IllegalArgumentException("tool messages must contain exactly one tool result");
    }
  }

  public static AgentMessage system(String text) {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)));
  }

  public static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }
}
