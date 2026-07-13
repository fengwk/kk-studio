package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/** 文本消息内容。 */
public record TextMessageContent(String text) implements AgentMessageContent {
  public TextMessageContent {
    text = Objects.requireNonNull(text, "text");
  }
}
