package fun.fengwk.kkstudio.harness.api.session;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;

import java.util.Objects;

/** 文本消息内容。 */
public record TextMessageContent(String text, ProviderBlockData providerBlockData)
    implements AgentMessageContent {
  public TextMessageContent {
    text = Objects.requireNonNull(text, "text");
  }

  public TextMessageContent(String text) {
    this(text, null);
  }
}
