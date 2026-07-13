package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/** 模型思考内容。 */
public record ThinkingMessageContent(String text) implements AgentMessageContent {
  public ThinkingMessageContent {
    text = Objects.requireNonNull(text, "text");
  }
}
